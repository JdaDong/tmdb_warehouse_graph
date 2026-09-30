package com.tmdbwh.governance.lifecycle;

import com.tmdbwh.common.clickhouse.ClickHouseClient;
import com.tmdbwh.common.clickhouse.ClickHouseSql;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 生命周期执行器：生成并执行 TTL / 冷下沉 / 分区清理。
 *
 * <p><b>默认 dry-run</b>：这些 DDL 会真的删数据，默认只看计划，
 * 由运维确认后再加 {@code --apply} 执行（配置 {@code tmdbwh.governance.lifecycle.dry-run}）。
 *
 * <p>执行结果写入 {@code governance.lifecycle_execution}，保留"计划 vs 实际"，
 * 便于审计"数据是什么时候按什么策略被删的"——这在合规场景里是必须的。
 */
public class LifecycleManager {

    private static final Logger LOG = LoggerFactory.getLogger(LifecycleManager.class);

    /** 一次计划动作。 */
    public static final class Action {

        private final String policy;
        private final String type;
        private final String sql;
        private final String database;
        private final String table;

        public Action(String policy, String type, String sql, String database, String table) {
            this.policy = policy;
            this.type = type;
            this.sql = sql;
            this.database = database;
            this.table = table;
        }

        public String getPolicy() {
            return policy;
        }

        public String getType() {
            return type;
        }

        public String getSql() {
            return sql;
        }

        public String getDatabase() {
            return database;
        }

        public String getTable() {
            return table;
        }

        @Override
        public String toString() {
            return type + " " + database + "." + table + ": " + sql;
        }
    }

    private final ClickHouseClient client;
    private final List<LifecyclePolicy> policies;
    private final boolean dryRun;
    private final String cluster;

    public LifecycleManager(ClickHouseClient client, List<LifecyclePolicy> policies, boolean dryRun,
            String cluster) {
        this.client = Objects.requireNonNull(client, "client");
        this.policies = List.copyOf(Objects.requireNonNull(policies, "policies"));
        this.dryRun = dryRun;
        this.cluster = cluster == null ? "" : cluster;
    }

    /** 从默认配置加载（dry-run 由配置决定）。 */
    public static LifecycleManager create(ClickHouseClient client, String cluster) {
        Config config = ConfigFactory.load().getConfig("tmdbwh.governance.lifecycle");
        List<LifecyclePolicy> policies = new ArrayList<>();
        config.getConfigList("policies").forEach(c -> policies.add(LifecyclePolicy.from(c)));
        return new LifecycleManager(client, policies, config.getBoolean("dry-run"), cluster);
    }

    /**
     * 生成全部动作。
     *
     * @param today 基准日期（用于计算需要清理的分区）
     */
    public List<Action> plan(LocalDate today) {
        List<Action> actions = new ArrayList<>();
        for (LifecyclePolicy policy : policies) {
            actions.addAll(planFor(policy, today));
        }
        return actions;
    }

    /** 单表动作生成。 */
    public List<Action> planFor(LifecyclePolicy policy, LocalDate today) {
        List<Action> actions = new ArrayList<>();
        String onCluster = ClickHouseSql.onCluster(cluster);
        if (policy.getTtlDays() > 0) {
            String ttl = "ALTER TABLE " + policy.getTable() + onCluster
                    + " MODIFY TTL dt + INTERVAL " + policy.getTtlDays() + " DAY";
            if (policy.getColdAfterDays() > 0) {
                ttl += ", dt + INTERVAL " + policy.getColdAfterDays() + " DAY TO VOLUME 'cold'";
            }
            actions.add(new Action("TTL", "SET_TTL", ttl + ";", policy.getDatabase(), policy.getTableName()));
        }
        if (policy.getPartitionDays() > 0) {
            LocalDate cutoff = today.minusDays(policy.getPartitionDays());
            // 只清理截止月份之前的整月分区：按月分区时无法只删"部分天数"
            actions.add(new Action("PARTITION_RETENTION", "DROP_PARTITION",
                    "ALTER TABLE " + policy.getTable() + onCluster + " DROP PARTITION ID '"
                            + partitionId(cutoff.withDayOfMonth(1).minusDays(1)) + "';",
                    policy.getDatabase(), policy.getTableName()));
        }
        return actions;
    }

    /**
     * 执行动作并记录。
     *
     * @param today 基准日期
     * @return 执行记录（含状态）
     */
    public List<String> apply(LocalDate today) {
        List<Action> actions = plan(today);
        List<String> log = new ArrayList<>();
        Instant now = Instant.now();
        for (Action action : actions) {
            String status;
            String detail = action.getSql();
            if (dryRun) {
                status = "SKIPPED";
                detail = "[dry-run] " + action.getSql();
                LOG.info("{}", detail);
            } else {
                try {
                    client.execute(action.getSql());
                    status = "APPLIED";
                } catch (RuntimeException e) {
                    status = "FAILED";
                    detail = action.getSql() + " | " + e.getMessage();
                    LOG.warn("生命周期动作失败: {}", detail);
                }
            }
            log.add(status + " " + action.getType() + " " + action.getDatabase() + "." + action.getTable());
            record(now, action, status, detail);
        }
        return log;
    }

    /** 写入执行记录（写入失败不影响主流程，但必须留日志）。 */
    private void record(Instant now, Action action, String status, String detail) {
        try {
            client.batchInsert("governance.lifecycle_execution",
                    List.of("executed_at", "database", "table", "policy", "action", "status", "detail"),
                    List.of(new Object[] {java.sql.Timestamp.from(now), action.getDatabase(), action.getTable(),
                            action.getPolicy(), action.getType(), status, detail}));
        } catch (RuntimeException e) {
            LOG.warn("生命周期执行记录写入失败: {}", e.toString());
        }
    }

    /** 分区 ID（yyyyMM），与迁移脚本中 PARTITION BY toYYYYMM(dt) 对应。 */
    public static String partitionId(LocalDate date) {
        return String.format("%04d%02d", date.getYear(), date.getMonthValue());
    }

    /** 默认 dry-run 取值（供 CLI 展示）。 */
    public static boolean defaultDryRun() {
        return ConfigFactory.load().getBoolean("tmdbwh.governance.lifecycle.dry-run");
    }

    /** 当前日期（UTC）。 */
    public static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }
}
