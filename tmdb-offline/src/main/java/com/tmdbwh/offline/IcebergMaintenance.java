package com.tmdbwh.offline;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Iceberg 表维护。
 *
 * <p>为什么需要它：Iceberg 每次写入都会产生新的快照与数据文件，长期不治理会出现三类问题——
 *
 * <ul>
 *   <li><b>快照无限增长</b>：元数据变多，查询规划变慢；
 *   <li><b>孤儿文件</b>：作业失败或重试留下的、没有任何快照引用的文件，持续占用对象存储成本；
 *   <li><b>小文件</b>：每天一批写入会产生大量小文件，Spark 读取时 task 数膨胀。
 * </ul>
 *
 * <p>维护动作统一通过 Iceberg 的 Spark 过程调用执行（{@code CALL catalog.system.xxx}），
 * 这样不需要额外引入 Iceberg 的 Java API，作业与集群的依赖保持一致。
 */
public final class IcebergMaintenance {

    private static final Logger LOG = LoggerFactory.getLogger(IcebergMaintenance.class);

    private IcebergMaintenance() {}

    /**
     * 生成过期快照的 SQL。
     *
     * @param table 表名（catalog 内，如 dwd.fact_movie_credit）
     * @param olderThanDays 早于该天数的快照将被清理（保留足够时间用于回滚与排障）
     */
    public static String expireSnapshots(String table, int olderThanDays) {
        checkTable(table);
        if (olderThanDays < 1) {
            throw new IllegalArgumentException("olderThanDays 必须 >= 1");
        }
        return "CALL " + SparkSupport.CATALOG + ".system.expire_snapshots("
                + "table => '" + table + "', older_than => TIMESTAMP '"
                + java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(olderThanDays)
                + " 00:00:00', retain_last => 3)";
    }

    /** 生成清理孤儿文件的 SQL（dryRun 为 true 时只报告不删除）。 */
    public static String removeOrphanFiles(String table, boolean dryRun) {
        checkTable(table);
        return "CALL " + SparkSupport.CATALOG + ".system.remove_orphan_files("
                + "table => '" + table + "', dry_run => " + dryRun + ")";
    }

    /** 生成小文件合并的 SQL（按 128MB 目标大小重写）。 */
    public static String rewriteDataFiles(String table) {
        checkTable(table);
        return "CALL " + SparkSupport.CATALOG + ".system.rewrite_data_files("
                + "table => '" + table + "', strategy => 'binpack',"
                + " options => map('target-file-size-bytes','134217728'))";
    }

    /**
     * 执行维护动作。
     *
     * @return 每条语句及其执行结果的日志行（便于 CLI 打印）
     */
    public static List<String> run(SparkSession spark, String table, boolean expire, boolean orphan,
            boolean compact, boolean dryRun) {
        Objects.requireNonNull(spark, "spark");
        List<String> sql = new ArrayList<>();
        if (expire) {
            sql.add(expireSnapshots(table, 7));
        }
        if (orphan) {
            sql.add(removeOrphanFiles(table, dryRun));
        }
        if (compact) {
            // 合并小文件是写操作，dry-run 时跳过，避免"说好不写却写了"
            if (dryRun) {
                LOG.info("dry-run：跳过小文件合并 {}", rewriteDataFiles(table));
            } else {
                sql.add(rewriteDataFiles(table));
            }
        }
        List<String> output = new ArrayList<>();
        for (String statement : sql) {
            LOG.info("执行 Iceberg 维护: {}", statement);
            if (dryRun) {
                output.add("[dry-run] " + statement);
                continue;
            }
            try {
                List<Row> rows = spark.sql(statement).collectAsList();
                String detail = rows.isEmpty() ? "完成" : String.join(", ",
                        rows.stream().map(r -> r.mkString("=")).collect(java.util.stream.Collectors.toList()));
                output.add(statement + " -> " + detail);
            } catch (RuntimeException e) {
                LOG.error("Iceberg 维护失败: {} | {}", statement, e.toString());
                output.add(statement + " -> 失败: " + e.toString());
            }
        }
        return output;
    }

    private static void checkTable(String table) {
        Objects.requireNonNull(table, "table");
        if (table.isBlank() || !table.matches("[a-z_][a-z0-9_]*\\.[a-z_][a-z0-9_]*")) {
            throw new IllegalArgumentException("表名应为 schema.table 形式且只含小写字母数字下划线: " + table);
        }
        if (table.toLowerCase(Locale.ROOT).contains("drop") || table.contains(";")) {
            throw new IllegalArgumentException("非法表名: " + table);
        }
    }
}
