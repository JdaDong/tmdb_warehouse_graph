package com.tmdbwh.offline;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.functions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 缓慢变化维（SCD2）合并。
 *
 * <p>为什么用 SCD2：TMDB 的 popularity / vote_count / revenue 会持续变化。若维度直接覆盖更新，
 * 历史事实表会"回溯变形"——同一份报表今天重跑和昨天跑结果不同，无法对账。SCD2 保留完整版本链，
 * 事实表按 valid_from / valid_to 关联当时有效的版本。
 *
 * <p>合并语义（针对一批新版本）：
 *
 * <ol>
 *   <li>新版本：{@code valid_from = 业务日期}，{@code valid_to = 2099-12-31}（哨兵值，表示未失效）；
 *   <li>已有版本：把 {@code valid_to} 收敛到新版本的 {@code valid_from}，
 *       区间取闭区间 {@code [valid_from, valid_to]}，因此相邻版本首尾相接、不重叠不留空洞；
 *   <li><b>内容未变化的实体不产生新版本</b>：比较业务列哈希，避免每天为几十万部电影各写一行；
 *   <li>幂等：同一业务日期重复执行结果完全一致（判定只依赖内容，不依赖执行次数）；
 *   <li>is_current 由 valid_to 是否为哨兵值推导，不再单独维护（避免与有效期不一致）。
 * </ol>
 */
public final class Scd2 {

    private static final Logger LOG = LoggerFactory.getLogger(Scd2.class);

    private Scd2() {}

    /**
     * 合并新版本到已有维度。
     *
     * @param existing 已有版本链；为 null 时表示首次构建
     * @param incoming 新版本，必须包含业务列，且已含 {@code valid_from}（timestamp 或可转换的字符串）
     * @param key 自然键列名（如 movie_id）
     * @param businessColumns 参与变化检测的业务列名
     * @return 合并后的完整版本链（按 key、valid_from 排序）
     */
    public static Dataset<Row> merge(Dataset<Row> existing, Dataset<Row> incoming, String key,
            List<String> businessColumns) {
        Objects.requireNonNull(incoming, "incoming");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(businessColumns, "businessColumns");
        if (businessColumns.isEmpty()) {
            throw new IllegalArgumentException("businessColumns 不能为空");
        }

        Dataset<Row> target = existing == null ? incoming.limit(0) : existing;

        Dataset<Row> newVersions = incoming
                .withColumn("hash", hashOf(incoming, businessColumns))
                .withColumn("valid_from", functions.col("valid_from").cast("timestamp"))
                .withColumn("valid_to", Columns.farFuture());

        // 每个实体当前有效版本的内容哈希
        Dataset<Row> latest = target
                .withColumn("latest_hash", hashOf(target, businessColumns))
                .filter(functions.col("valid_to").cast("timestamp").equalTo(Columns.farFuture()))
                .select(functions.col(key).as(key), functions.col("latest_hash"));

        // 只保留"内容确实发生变化"的实体；左连接后 latest_hash 为 NULL 表示新实体
        Dataset<Row> changed = newVersions
                .join(latest, ScalaSeqs.of(key), "left")
                .filter(functions.col("latest_hash").isNull()
                        .or(functions.col("latest_hash").notEqual(functions.col("hash"))))
                .drop("latest_hash");

        long incomingCount = -1;
        if (LOG.isInfoEnabled()) {
            incomingCount = newVersions.count();
        }
        changed = changed.persist();
        long changedCount = changed.count();
        LOG.info("SCD2：输入 {} 条，内容发生变化的 {} 条", incomingCount, changedCount);

        Dataset<Row> result;
        if (changedCount == 0) {
            result = target;
        } else {
            Dataset<Row> closed = closeExisting(target, changed, key);
            result = closed.unionByName(changed.select(selectAllAsColumns(target)));
        }
        changed.unpersist();
        return result.dropDuplicates(key, "valid_from").sort(functions.col(key), functions.col("valid_from"));
    }

    /**
     * 收敛已有版本的失效时间：与新版本 valid_from 取较小值。
     *
     * <p>用 {@code least} 而不是直接赋值，是为了让"重跑同一天"保持幂等——
     * 已被更早版本收敛过的行不会被再次放宽。
     */
    static Dataset<Row> closeExisting(Dataset<Row> target, Dataset<Row> changed, String key) {
        Dataset<Row> boundaries = changed.select(functions.col(key), functions.col("valid_from")).distinct();
        return target
                .join(boundaries, ScalaSeqs.of(key), "left")
                .withColumn("valid_to", functions.least(
                        target.col("valid_to").cast("timestamp"),
                        functions.coalesce(boundaries.col("valid_from").cast("timestamp"),
                                target.col("valid_to").cast("timestamp"))))
                .select(selectAllAsColumns(target));
    }

    /**
     * join 后同名列会被 Spark 自动改写（或产生重复列），这里显式按目标列取一次，
     * 保证输出 Schema 与目标表一致；同时适配 select(Column...) 重载。
     */
    private static Column[] selectAllAsColumns(Dataset<Row> target) {
        String[] names = target.columns();
        Column[] columns = new Column[names.length];
        for (int i = 0; i < names.length; i++) {
            columns[i] = target.col(names[i]).as(names[i]);
        }
        return columns;
    }

    /**
     * 业务列的内容哈希：列顺序固定、NULL 视为空串，保证同一内容得到同一结果。
     *
     * <p>与 ClickHouse 侧的 {@code cityHash64(concat(...))} 口径等价（见 sql/clickhouse 资源文件）。
     */
    public static Column hashOf(Dataset<Row> dataset, List<String> businessColumns) {
        Column[] cols = businessColumns.stream()
                .map(c -> functions.coalesce(dataset.col(c).cast("string"), functions.lit("")))
                .toArray(Column[]::new);
        return functions.sha2(functions.concat_ws("|", cols), 256);
    }

    /** 便捷重载。 */
    public static Column hashOf(Dataset<Row> dataset, String... businessColumns) {
        return hashOf(dataset, Arrays.asList(businessColumns));
    }
}
