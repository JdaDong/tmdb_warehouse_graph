package com.tmdbwh.governance.migration;

import com.tmdbwh.common.clickhouse.ClickHouseClient;
import com.tmdbwh.common.clickhouse.ClickHouseSql;
import com.tmdbwh.common.clickhouse.SqlScriptSplitter;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ClickHouse 版本化迁移器。
 *
 * <p>约定与保证：
 *
 * <ul>
 *   <li>脚本位于 {@code clickhouse/migrations/V<n>__<name>.sql}，按版本号升序<b>只增不改</b>；
 *   <li>执行记录在 {@code governance.schema_migrations}（版本、名称、校验和、耗时、执行人）；
 *   <li><b>校验和防篡改</b>：已执行脚本的内容若被修改，下次运行立即报错，而不是静默跳过；
 *   <li><b>版本回退保护</b>：脚本中的最高版本低于库内版本时报错（通常是分支合并丢了脚本）；
 *   <li>所有 DDL 都写成幂等形式（{@code IF NOT EXISTS} / {@code IF EXISTS}），便于失败后修正重跑；
 *   <li>集群模式下自动追加 {@code ON CLUSTER <cluster>} 并 {@code SYNC} 等待各节点完成，
 *       脚本本身无需关心单机还是集群。
 * </ul>
 *
 * <p>用法：
 *
 * <pre>{@code
 * try (ClickHouseClient client = ClickHouseClient.create(config)) {
 *     SchemaMigrator migrator = new SchemaMigrator(client, config.getCluster(), "tmdbwh");
 *     MigrationResult result = migrator.migrate(false);
 * }
 * }</pre>
 */
public class SchemaMigrator implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaMigrator.class);

    /** 迁移脚本在 classpath 中的目录。 */
    public static final String DEFAULT_RESOURCE_DIR = "clickhouse/migrations";
    /** 记录表所在的库。 */
    public static final String HISTORY_DATABASE = "governance";
    /** 记录表名。 */
    public static final String HISTORY_TABLE = "schema_migrations";

    private static final int MAX_VERSION = 100_000;

    private final ClickHouseClient client;
    private final String cluster;
    private final String appliedBy;
    private final String resourceDir;
    private final boolean ownsClient;

    public SchemaMigrator(ClickHouseClient client, String cluster, String appliedBy) {
        this(client, cluster, appliedBy, DEFAULT_RESOURCE_DIR);
    }

    /**
     * 使用自定义脚本目录，且<b>不接管</b>客户端生命周期（try-with-resources 场景）。
     *
     * @param resourceDir 迁移脚本所在的 classpath 目录（测试或自定义流程使用）
     */
    public static SchemaMigrator using(ClickHouseClient client, String cluster, String appliedBy, String resourceDir) {
        return new SchemaMigrator(client, cluster, appliedBy, resourceDir, false);
    }

    /**
     * @param client ClickHouse 客户端（由本类关闭）
     * @param cluster 集群名，空串表示单机
     * @param appliedBy 执行人 / 系统标识，写入历史表便于审计
     * @param resourceDir 迁移脚本所在的 classpath 目录
     */
    public SchemaMigrator(ClickHouseClient client, String cluster, String appliedBy, String resourceDir) {
        this(client, cluster, appliedBy, resourceDir, true);
    }

    /** {@code ownsClient=false} 时由调用方负责关闭客户端（try-with-resources 场景）。 */
    public static SchemaMigrator using(ClickHouseClient client, String cluster, String appliedBy) {
        return using(client, cluster, appliedBy, DEFAULT_RESOURCE_DIR);
    }

    private SchemaMigrator(ClickHouseClient client, String cluster, String appliedBy, String resourceDir,
            boolean ownsClient) {
        this.client = Objects.requireNonNull(client, "client");
        this.cluster = cluster == null ? "" : cluster;
        this.appliedBy = appliedBy == null || appliedBy.isEmpty() ? "unknown" : appliedBy;
        this.resourceDir = Objects.requireNonNull(resourceDir, "resourceDir");
        this.ownsClient = ownsClient;
    }

    /**
     * 执行迁移。
     *
     * @param dryRun true 时只打印计划，不实际执行
     * @return 执行结果
     * @throws MigrationException 校验和不匹配 / 版本回退 / 脚本缺失
     */
    public MigrationResult migrate(boolean dryRun) {
        return migrate(dryRun, null);
    }

    /**
     * 执行迁移（可附带额外脚本目录）。
     *
     * @param extraDir 额外脚本目录（运维自定义迁移），可为空
     */
    public MigrationResult migrate(boolean dryRun, Path extraDir) {
        List<Migration> scripts = loadScripts(extraDir);
        ensureHistoryTable();
        Map<Integer, String> applied = readAppliedChecksums();
        verifyChecksums(scripts, applied);

        int current = applied.isEmpty() ? 0 : maxKey(applied);
        int target = scripts.get(scripts.size() - 1).getVersion();
        if (target < current) {
            throw new MigrationException(String.format(
                    "迁移脚本最高版本 V%d 低于库内已执行版本 V%d，可能存在脚本丢失（分支合并？）", target, current));
        }

        List<Migration> pending = new ArrayList<>();
        for (Migration script : scripts) {
            if (!applied.containsKey(script.getVersion())) {
                pending.add(script);
            }
        }
        MigrationPlan plan = new MigrationPlan(pending, current, target);
        LOG.info("迁移计划 {}", plan);

        if (dryRun) {
            for (Migration script : pending) {
                LOG.info("  [dry-run] 将执行 {}（{} 条语句）", script, script.statementCount());
            }
            return new MigrationResult(current, current);
        }

        MigrationResult result = new MigrationResult(current, current);
        for (Migration script : pending) {
            apply(script, result);
        }
        LOG.info(result.summary());
        return result;
    }

    /** 计算执行计划但不执行。 */
    public MigrationPlan plan() {
        List<Migration> scripts = loadScripts(null);
        Map<Integer, String> applied = client.tableExists(HISTORY_DATABASE + "." + HISTORY_TABLE)
                ? readAppliedChecksums()
                : Map.of();
        List<Migration> pending = new ArrayList<>();
        for (Migration script : scripts) {
            if (!applied.containsKey(script.getVersion())) {
                pending.add(script);
            }
        }
        int current = applied.isEmpty() ? 0 : maxKey(applied);
        return new MigrationPlan(pending, current, scripts.get(scripts.size() - 1).getVersion());
    }

    /** 已执行的迁移历史（按版本升序）。 */
    public List<Map<String, Object>> history() {
        ensureHistoryTable();
        return client.query("SELECT version, name, checksum, executed_at, duration_ms, applied_by, success"
                + " FROM " + HISTORY_DATABASE + "." + HISTORY_TABLE + " ORDER BY version",
                rs -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("version", rs.getInt(1));
                    row.put("name", rs.getString(2));
                    row.put("checksum", rs.getString(3));
                    row.put("executedAt", rs.getString(4));
                    row.put("durationMs", rs.getLong(5));
                    row.put("appliedBy", rs.getString(6));
                    row.put("success", rs.getInt(7) == 1);
                    return row;
                });
    }

    private List<Migration> loadScripts(Path extraDir) {
        List<Migration> scripts;
        try {
            scripts = new ArrayList<>(MigrationLoader.fromClasspath(resourceDir));
            if (extraDir != null) {
                scripts.addAll(MigrationLoader.fromDirectory(extraDir));
            }
        } catch (IOException e) {
            throw new MigrationException("读取迁移脚本失败", e);
        }
        Collections.sort(scripts);
        return scripts;
    }

    private void apply(Migration script, MigrationResult result) {
        long start = System.nanoTime();
        int statements = 0;
        try {
            List<String> sqls = SqlScriptSplitter.split(script.getSql());
            for (String sql : sqls) {
                client.execute(withCluster(sql));
                statements++;
            }
        } catch (RuntimeException e) {
            record(script, false, Duration.ofNanos(System.nanoTime() - start), statements);
            throw new MigrationException(String.format("执行迁移 V%d 失败（已成功执行 %d/%d 条语句）: %s",
                    script.getVersion(), statements, script.statementCount(), e.getMessage()), e);
        }
        Duration cost = Duration.ofNanos(System.nanoTime() - start);
        record(script, true, cost, statements);
        result.add(new MigrationResult.Applied(script.getVersion(), script.getName(), statements, cost));
        LOG.info("已执行迁移 {}（{} 条语句，{} ms）", script, statements, cost.toMillis());
    }

    private void record(Migration script, boolean success, Duration cost, int statements) {
        try {
            client.execute("INSERT INTO " + HISTORY_DATABASE + "." + HISTORY_TABLE
                    + " (version, name, checksum, executed_at, duration_ms, applied_by, success, statements) VALUES"
                    + " (" + script.getVersion()
                    + ", " + ClickHouseSql.quoteString(script.getName())
                    + ", " + ClickHouseSql.quoteString(script.getChecksum())
                    + ", now(), " + cost.toMillis()
                    + ", " + ClickHouseSql.quoteString(appliedBy)
                    + ", " + (success ? 1 : 0)
                    + ", " + statements + ")");
        } catch (RuntimeException e) {
            // 历史记录写失败不能掩盖迁移本身的结果，但必须明确告警，避免"执行过却没记录"
            LOG.error("写入迁移历史失败 V{}: {}", script.getVersion(), e.toString());
        }
    }

    private void ensureHistoryTable() {
        String qualified = HISTORY_DATABASE + "." + HISTORY_TABLE;
        client.execute("CREATE TABLE IF NOT EXISTS " + qualified + ClickHouseSql.onCluster(cluster)
                + " (version UInt32 COMMENT '迁移版本号',"
                + " name String COMMENT '脚本名称（不含版本前缀）',"
                + " checksum String COMMENT '脚本内容校验和，用于检测已执行脚本被修改',"
                + " executed_at DateTime COMMENT '执行时间',"
                + " duration_ms UInt64 COMMENT '执行耗时（毫秒）',"
                + " applied_by String COMMENT '执行人 / 调度系统',"
                + " success UInt8 COMMENT '1 成功 / 0 失败',"
                + " statements UInt32 COMMENT '本次执行的语句条数')"
                + " ENGINE = ReplacingMergeTree(executed_at)"
                + " ORDER BY version"
                + " SETTINGS index_granularity = 8192");
        if (!cluster.isEmpty()) {
            // 等待副本同步，避免后续 INSERT 时部分节点上表还不存在
            try {
                client.execute("SYSTEM SYNC REPLICA " + qualified);
            } catch (RuntimeException e) {
                LOG.debug("SYSTEM SYNC REPLICA 跳过（非副本表或不支持）: {}", e.toString());
            }
        }
    }

    private Map<Integer, String> readAppliedChecksums() {
        return client.query("SELECT version, argMax(checksum, executed_at) FROM "
                        + HISTORY_DATABASE + "." + HISTORY_TABLE + " GROUP BY version",
                rs -> new AbstractMap.SimpleEntry<>(rs.getInt(1), rs.getString(2)))
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private void verifyChecksums(List<Migration> scripts, Map<Integer, String> applied) {
        Map<Integer, Migration> byVersion = new LinkedHashMap<>();
        for (Migration script : scripts) {
            byVersion.put(script.getVersion(), script);
        }
        for (Map.Entry<Integer, String> entry : applied.entrySet()) {
            Migration script = byVersion.get(entry.getKey());
            if (script == null) {
                throw new MigrationException(String.format(
                        "库中记录的迁移 V%d 在当前脚本目录中不存在（脚本被删除？）", entry.getKey()));
            }
            if (!script.getChecksum().equals(entry.getValue())) {
                throw new MigrationException(String.format(
                        "迁移 V%d（%s）的内容与库中记录不一致：校验和 %s != %s。请勿修改已执行的脚本，应新增脚本修正。",
                        entry.getKey(), script.getName(), script.getChecksum(), entry.getValue()));
            }
        }
    }

    /** 为 DDL 追加 ON CLUSTER（集群模式）；非 DDL 或已包含该子句时原样返回。 */
    String withCluster(String sql) {
        String trimmed = sql.trim();
        if (cluster.isEmpty()) {
            return sql;
        }
        String upper = trimmed.toUpperCase(Locale.ROOT);
        if (upper.contains("ON CLUSTER")) {
            return sql;
        }
        boolean isDdl = upper.startsWith("CREATE") || upper.startsWith("ALTER") || upper.startsWith("DROP")
                || upper.startsWith("TRUNCATE") || upper.startsWith("RENAME") || upper.startsWith("SYSTEM");
        if (!isDdl) {
            return sql;
        }
        return trimmed + " ON CLUSTER " + ClickHouseSql.identifier(cluster);
    }

    private static int maxKey(Map<Integer, ?> map) {
        int max = 0;
        for (int key : map.keySet()) {
            max = Math.max(max, key);
        }
        return max;
    }

    @Override
    public void close() {
        if (ownsClient) {
            client.close();
        }
    }
}
