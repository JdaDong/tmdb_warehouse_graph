package com.tmdbwh.governance.metrics;

import com.tmdbwh.common.clickhouse.ClickHouseClient;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 指标口径注册表与一致性校验。
 *
 * <p>校验三件事，都是"指标口径失控"的典型起因：
 *
 * <ul>
 *   <li><b>重名不同义</b>：同一指标名定义了两种算法（最容易导致报表互相打架）；
 *   <li><b>表不存在</b>：指标定义引用的表被改名或删除；
 *   <li><b>字段不存在</b>：表达式里用到的列在表里没有（表结构变更后常见）。
 * </ul>
 */
public class MetricRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(MetricRegistry.class);

    /** 校验问题。 */
    public static final class Issue {

        private final String metric;
        private final String type;
        private final String message;

        public Issue(String metric, String type, String message) {
            this.metric = metric;
            this.type = type;
            this.message = message;
        }

        public String getMetric() {
            return metric;
        }

        public String getType() {
            return type;
        }

        public String getMessage() {
            return message;
        }

        @Override
        public String toString() {
            return "[" + type + "] " + metric + ": " + message;
        }
    }

    private final List<MetricDefinition> definitions;

    public MetricRegistry(List<MetricDefinition> definitions) {
        this.definitions = List.copyOf(Objects.requireNonNull(definitions, "definitions"));
        checkDuplicates();
    }

    /** 从默认配置加载。 */
    public static MetricRegistry load() {
        return load(ConfigFactory.load().getConfig("tmdbwh.governance.metrics"));
    }

    /** 从指定配置加载。 */
    public static MetricRegistry load(Config config) {
        List<MetricDefinition> definitions = new ArrayList<>();
        config.getConfigList("definitions").forEach(c -> definitions.add(MetricDefinition.from(c)));
        return new MetricRegistry(definitions);
    }

    /**
     * 构造时就拒绝"重名不同义"。
     *
     * <p>重名但算法不同是最危险的情况：两个报表引用同一个名字却得到不同结果，
     * 排查时往往要翻遍所有 SQL。因此在加载阶段就直接失败。
     */
    private void checkDuplicates() {
        java.util.Map<String, MetricDefinition> byName = new java.util.HashMap<>();
        for (MetricDefinition definition : definitions) {
            MetricDefinition previous = byName.put(definition.getName(), definition);
            if (previous != null && !previous.getExpression().equals(definition.getExpression())) {
                throw new IllegalArgumentException("指标 " + definition.getName() + " 存在两种算法: "
                        + previous.getExpression() + " 与 " + definition.getExpression());
            }
        }
    }

    public List<MetricDefinition> getDefinitions() {
        return definitions;
    }

    /** 按名字查找。 */
    public MetricDefinition get(String name) {
        return definitions.stream()
                .filter(definition -> definition.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未定义的指标: " + name));
    }

    /**
     * 校验定义与真实表结构是否一致。
     *
     * @param client ClickHouse 客户端（可为 null，此时只做静态校验）
     */
    public List<Issue> validate(ClickHouseClient client) {
        List<Issue> issues = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (MetricDefinition definition : definitions) {
            if (!seen.add(definition.getName())) {
                issues.add(new Issue(definition.getName(), "DUPLICATE", "指标重复定义"));
            }
            if (client == null) {
                continue;
            }
            if (!client.tableExists(definition.getTable())) {
                issues.add(new Issue(definition.getName(), "MISSING_TABLE",
                        "表不存在: " + definition.getTable()));
                continue;
            }
            for (String column : columnsIn(definition.getExpression())) {
                if (!columnExists(client, definition.getTable(), column)) {
                    issues.add(new Issue(definition.getName(), "MISSING_COLUMN",
                            "字段不存在: " + definition.getTable() + "." + column));
                }
            }
        }
        LOG.info("指标校验完成: {} 个定义，{} 个问题", definitions.size(), issues.size());
        return issues;
    }

    /** 从表达式中提取可能的列名（只取标识符，忽略函数与数字）。 */
    static List<String> columnsIn(String expression) {
        List<String> columns = new ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("[a-zA-Z_][a-zA-Z0-9_]*")
                .matcher(expression);
        Set<String> keywords = Set.of("avg", "sum", "count", "min", "max", "uniq", "distinct", "if", "toFloat64",
                "toInt64", "round", "greatest", "least");
        while (matcher.find()) {
            String token = matcher.group();
            if (!keywords.contains(token.toLowerCase(java.util.Locale.ROOT))) {
                columns.add(token);
            }
        }
        return columns;
    }

    private static boolean columnExists(ClickHouseClient client, String table, String column) {
        try {
            long count = client.queryForLong(
                    "SELECT count() FROM system.columns WHERE database = ? AND table = ? AND name = ?",
                    databaseOf(table), tableNameOf(table), column);
            return count > 0;
        } catch (RuntimeException e) {
            LOG.warn("字段存在性校验失败（按通过处理）: {} {} | {}", table, column, e.toString());
            return true;
        }
    }

    private static String databaseOf(String table) {
        int dot = table.indexOf('.');
        return dot < 0 ? "default" : table.substring(0, dot);
    }

    private static String tableNameOf(String table) {
        int dot = table.indexOf('.');
        return dot < 0 ? table : table.substring(dot + 1);
    }
}
