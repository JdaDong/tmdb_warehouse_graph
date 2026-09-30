package com.tmdbwh.offline;

import java.util.Objects;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SaveMode;
import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 湖仓（Iceberg）表的读写。
 *
 * <p>分工：
 *
 * <ul>
 *   <li>本类负责"把 Dataset 落到 Iceberg 表"，分区按业务日期（dt）；
 *   <li>ClickHouse 侧的分区替换由 {@link OfflineSqlRunner} 生成的 SQL 完成，两者按同一 dt 对齐；
 *   <li><b>重复写入语义</b>：{@link #writePartition} 使用动态分区覆盖，
 *       同一业务日期重跑得到完全相同的分区内容——这是"重跑结果一致"的基础。
 * </ul>
 */
public final class LakeTables {

    private static final Logger LOG = LoggerFactory.getLogger(LakeTables.class);

    private LakeTables() {}

    /** 表名（catalog 内）。 */
    public static String qualified(String schema, String table) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(table, "table");
        return schema + "." + table;
    }

    /**
     * 按业务日期覆盖写入分区。
     *
     * @param spark 会话
     * @param dataset 数据，必须含 dt 列
     * @param schema 库名（ods / dwd / dws / ads）
     * @param table 表名
     */
    public static void writePartition(SparkSession spark, Dataset<Row> dataset, String schema, String table) {
        Objects.requireNonNull(dataset, "dataset");
        String target = qualified(schema, table);
        LOG.info("写入湖仓表 {}（分区覆盖，{} 行）", target, dataset.count());
        // 动态分区覆盖：只替换本次涉及的 dt 分区，其他分区不受影响
        dataset.write()
                .format("iceberg")
                .mode(SaveMode.Overwrite)
                .option("spark.sql.sources.partitionOverwriteMode", "dynamic")
                .partitionBy("dt")
                .saveAsTable(target);
    }

    /** 全量覆盖写入（维度表用：整张表重写，保证 SCD2 版本链完整一致）。 */
    public static void writeOverwrite(Dataset<Row> dataset, String schema, String table) {
        Objects.requireNonNull(dataset, "dataset");
        String target = qualified(schema, table);
        LOG.info("全量覆盖写入湖仓表 {}（{} 行）", target, dataset.count());
        dataset.write().format("iceberg").mode(SaveMode.Overwrite).saveAsTable(target);
    }

    /** 追加写入。 */
    public static void writeAppend(Dataset<Row> dataset, String schema, String table) {
        Objects.requireNonNull(dataset, "dataset");
        String target = qualified(schema, table);
        LOG.info("追加写入湖仓表 {}（{} 行）", target, dataset.count());
        dataset.write().format("iceberg").mode(SaveMode.Append).saveAsTable(target);
    }

    /** 读取整张表；不存在时返回 null（首次运行）。 */
    public static Dataset<Row> readOrNull(SparkSession spark, String schema, String table) {
        String target = qualified(schema, table);
        if (!spark.catalog().tableExists(target)) {
            LOG.info("湖仓表不存在，按首次运行处理: {}", target);
            return null;
        }
        return spark.read().format("iceberg").load(target);
    }

    /** 表是否存在。 */
    public static boolean exists(SparkSession spark, String schema, String table) {
        return spark.catalog().tableExists(qualified(schema, table));
    }
}
