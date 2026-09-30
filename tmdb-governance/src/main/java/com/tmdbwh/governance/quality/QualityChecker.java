package com.tmdbwh.governance.quality;

import com.tmdbwh.common.clickhouse.ClickHouseClient;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 数据质量检查执行器。
 *
 * <p>设计要点：
 *
 * <ul>
 *   <li><b>规则失败不等于检查失败</b>：单条规则查询报错时记为一条失败结果并继续，
 *       否则一次网络抖动就会让整轮检查停摆（而质量检查正是要在异常时发现问题的）；
 *   <li><b>结果入库</b>：结果写入 {@code governance.dq_result}，用于趋势观察与复盘，
 *       只看当次日志无法回答"这个问题持续多久了"；
 *   <li><b>BLOCKER 决定是否阻断</b>：由 {@code fail-on-blocker} 控制，
 *       上线初期可先关掉只观察，稳定后再打开。
 * </ul>
 */
public class QualityChecker {

    private static final Logger LOG = LoggerFactory.getLogger(QualityChecker.class);

    private final ClickHouseClient client;
    private final List<QualityRule> rules;
    private final boolean failOnBlocker;

    public QualityChecker(ClickHouseClient client, List<QualityRule> rules, boolean failOnBlocker) {
        this.client = Objects.requireNonNull(client, "client");
        this.rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
        this.failOnBlocker = failOnBlocker;
    }

    /** 从默认配置加载规则。 */
    public static QualityChecker create(ClickHouseClient client) {
        Config config = ConfigFactory.load().getConfig("tmdbwh.governance.quality");
        List<QualityRule> rules = new ArrayList<>();
        config.getConfigList("rules").forEach(c -> rules.add(QualityRule.from(c)));
        return new QualityChecker(client, rules, config.getBoolean("fail-on-blocker"));
    }

    /**
     * 执行全部规则。
     *
     * @param dt 业务日期（限定分区）；为空表示不做分区过滤
     * @return 检查结果汇总
     */
    public QualityReport run(String dt) {
        Instant checkedAt = Instant.now();
        List<QualityResult> results = new ArrayList<>();
        for (QualityRule rule : rules) {
            results.add(evaluate(rule, dt, checkedAt));
        }
        persist(results);
        QualityReport report = new QualityReport(results, checkedAt);
        LOG.info("质量检查完成: 共 {} 条，通过 {} 条，BLOCKER 失败 {} 条", report.total(), report.passed(),
                report.blockerFailures());
        return report;
    }

    /** 执行单条规则；查询异常时记为失败结果而不是中断整轮检查。 */
    QualityResult evaluate(QualityRule rule, String dt, Instant checkedAt) {
        try {
            Double metric = client.query(rule.toSql(dt), rs -> {
                double value = rs.getDouble("metric");
                return rs.wasNull() ? null : value;
            }).stream().findFirst().orElse(null);
            return judge(rule, metric, checkedAt);
        } catch (RuntimeException e) {
            LOG.warn("规则 {} 执行失败: {}", rule.getId(), e.toString());
            return new QualityResult(rule.getId(), rule.getDatabase(), rule.getTableName(), rule.getColumn(),
                    rule.getSeverity(), false, null, null, "规则执行失败: " + e.getMessage(), checkedAt);
        }
    }

    /** 按规则类型判定是否通过。 */
    QualityResult judge(QualityRule rule, Double metric, Instant checkedAt) {
        if (metric == null) {
            return new QualityResult(rule.getId(), rule.getDatabase(), rule.getTableName(), rule.getColumn(),
                    rule.getSeverity(), false, null, null, "未获取到指标值", checkedAt);
        }
        boolean passed;
        Double threshold;
        String message;
        switch (rule.getType()) {
            case ROW_COUNT:
                threshold = rule.getMin();
                passed = threshold == null || metric >= threshold;
                message = "行数 " + metric.longValue() + "，下限 " + threshold;
                break;
            case UNIQUE:
                threshold = rule.getMax();
                passed = metric <= (threshold == null ? 0 : threshold);
                message = "重复组合数 " + metric.longValue();
                break;
            case NOT_NULL:
                threshold = rule.getMax();
                passed = metric <= (threshold == null ? 0.0 : threshold);
                message = "空值比例 " + String.format("%.4f", metric);
                break;
            case RANGE:
                threshold = 0.0;
                passed = metric <= 0.0;
                message = "越界行数 " + metric.longValue() + "（区间 [" + rule.getMin() + ", " + rule.getMax() + "]）";
                break;
            case FRESHNESS:
                threshold = rule.getMax();
                passed = threshold == null || metric <= threshold;
                message = "数据滞后 " + metric.longValue() + " 天，上限 " + threshold;
                break;
            case REFERENTIAL:
                threshold = rule.getMax();
                passed = metric <= (threshold == null ? 0 : threshold);
                message = "孤儿记录数 " + metric.longValue();
                break;
            default:
                throw new IllegalStateException("未支持的规则类型: " + rule.getType());
        }
        return new QualityResult(rule.getId(), rule.getDatabase(), rule.getTableName(), rule.getColumn(),
                rule.getSeverity(), passed, metric, threshold, message, checkedAt);
    }

    /** 结果入库（失败不影响本次检查结果返回）。 */
    void persist(List<QualityResult> results) {
        if (results.isEmpty()) {
            return;
        }
        try {
            client.batchInsert("governance.dq_result", QualityResult.columns(),
                    results.stream().map(QualityResult::toRow).collect(java.util.stream.Collectors.toList()));
        } catch (RuntimeException e) {
            LOG.warn("质量结果入库失败（不影响本次检查）: {}", e.toString());
        }
    }

    /** 是否因 BLOCKER 失败而应阻断下游。 */
    public boolean shouldBlock(QualityReport report) {
        return failOnBlocker && report.blockerFailures() > 0;
    }

    /** 检查汇总。 */
    public static final class QualityReport {

        private final List<QualityResult> results;
        private final Instant checkedAt;

        public QualityReport(List<QualityResult> results, Instant checkedAt) {
            this.results = List.copyOf(results);
            this.checkedAt = checkedAt;
        }

        public List<QualityResult> getResults() {
            return results;
        }

        public Instant getCheckedAt() {
            return checkedAt;
        }

        public long total() {
            return results.size();
        }

        public long passed() {
            return results.stream().filter(QualityResult::isPassed).count();
        }

        public long failures() {
            return results.stream().filter(result -> !result.isPassed()).count();
        }

        public long blockerFailures() {
            return results.stream()
                    .filter(result -> !result.isPassed() && result.getSeverity() == QualityRule.Severity.BLOCKER)
                    .count();
        }

        /** 按规则 ID 查找结果。 */
        public Optional<QualityResult> resultOf(String ruleId) {
            return results.stream().filter(result -> result.getRuleId().equals(ruleId)).findFirst();
        }
    }
}
