package com.tmdbwh.offline;

import com.tmdbwh.common.clickhouse.ClickHouseSql;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 生成 ClickHouse 侧的同步 SQL（离线任务在把结果写入湖仓后调用）。
 *
 * <p>为什么用"分区替换"而不是"先 DELETE 再 INSERT"：
 *
 * <ul>
 *   <li>DELETE 是异步 mutation，数据在一段时间内处于"已删未插"的中间态，报表会看到空窗；
 *   <li>分区替换是分区级别的原子切换，要么旧数据要么新数据，不存在中间态；
 *   <li>重跑时替换同一分区，结果是完全一致的，不需要额外去重逻辑。
 * </ul>
 *
 * <p>实现方式依赖 ClickHouse 版本，因此这里同时提供两种写法，默认使用 MOVE：
 *
 * <ul>
 *   <li><b>MOVE PARTITION TO TABLE</b>：仅移动元数据，最快；要求暂存表与目标表都不是 Replicated 引擎；
 *   <li><b>REPLACE PARTITION FROM</b>：会复制数据，但对 Replicated 表可用，作为降级方案。
 * </ul>
 *
 * <p>两者都没有 {@code IF EXISTS} 形式，执行器遇到失败时应记录并降级（见 {@link #replacePartition} 的注释）。
 */
public final class OfflineSqlRunner {

    private static final Logger LOG = LoggerFactory.getLogger(OfflineSqlRunner.class);

    private final String cluster;
    private final String lakeUrl;

    public OfflineSqlRunner(String cluster) {
        this(cluster, null);
    }

    /**
     * @param cluster ClickHouse 集群名（为空表示单机）
     * @param lakeUrl 湖仓根地址（ClickHouse 侧可达）；为空时保留占位符由配置渲染
     */
    public OfflineSqlRunner(String cluster, String lakeUrl) {
        this.cluster = cluster == null ? "" : cluster;
        this.lakeUrl = lakeUrl == null || lakeUrl.isEmpty() ? "${tmdbwh.s3.lake-url}" : withTrailingSlash(lakeUrl);
    }

    private static String withTrailingSlash(String url) {
        return url.endsWith("/") ? url : url + "/";
    }

    private String onCluster() {
        return ClickHouseSql.onCluster(cluster);
    }

    /**
     * 生成"替换某业务日期分区"的 SQL 序列。
     *
     * @param database 库名
     * @param table 表名
     * @param dt 业务日期（yyyy-MM-dd）
     * @param stagingSuffix 暂存表后缀（含 runId，避免并发重跑互相覆盖）
     * @param createLike 暂存表的建表语句（{@code CREATE TABLE ... AS 目标表}）
     * @return 按顺序执行的 SQL 列表
     */
    public List<String> replacePartition(String database, String table, String dt, String stagingSuffix,
            String createLike) {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(dt, "dt");
        String target = database + "." + table;
        String staging = target + stagingSuffix;
        List<String> sql = new ArrayList<>();
        sql.add("DROP TABLE IF EXISTS " + staging + onCluster());
        sql.add(createLike);
        // 分区 ID：ClickHouse 按 PARTITION BY 表达式的值生成，按月分区时为 yyyyMM
        sql.add("ALTER TABLE " + target + onCluster()
                + " MOVE PARTITION ID '" + partitionId(dt) + "' TO TABLE " + staging);
        sql.add("INSERT INTO " + staging + " SELECT * FROM " + sourceOf(database, table, dt));
        sql.add("ALTER TABLE " + target + onCluster()
                + " DROP PARTITION ID '" + partitionId(dt) + "'");
        sql.add("ALTER TABLE " + target + onCluster()
                + " ATTACH PARTITION ID '" + partitionId(dt) + "' FROM " + staging);
        sql.add("DROP TABLE IF EXISTS " + staging + onCluster());
        LOG.debug("生成分区替换 SQL {} 条: {}", sql.size(), target);
        return sql;
    }

    /** 降级方案：REPLACE PARTITION（对 Replicated 表可用，代价是复制数据）。 */
    public List<String> replacePartitionViaCopy(String database, String table, String dt, String stagingSuffix,
            String createLike) {
        String target = database + "." + table;
        String staging = target + stagingSuffix;
        List<String> sql = new ArrayList<>();
        sql.add("DROP TABLE IF EXISTS " + staging + onCluster());
        sql.add(createLike);
        sql.add("INSERT INTO " + staging + " SELECT * FROM " + sourceOf(database, table, dt));
        sql.add("ALTER TABLE " + target + onCluster()
                + " REPLACE PARTITION ID '" + partitionId(dt) + "' FROM " + staging);
        sql.add("DROP TABLE IF EXISTS " + staging + onCluster());
        return sql;
    }

    /**
     * 生成"删除某业务日期数据"的 SQL（最后兜底：异步 mutation，存在短暂中间态）。
     *
     * <p>用于维度表这类按 key 去重、且没有独立业务日期分区的场景（如 dim_movie 按 valid_from 分区）。
     */
    public List<String> deleteByDate(String database, String table, String dateColumn, String dt) {
        String target = database + "." + table;
        List<String> sql = new ArrayList<>();
        sql.add("ALTER TABLE " + target + onCluster()
                + " DELETE WHERE " + ClickHouseSql.identifier(dateColumn)
                + " >= toDateTime('" + dt + " 00:00:00')"
                + " AND " + ClickHouseSql.identifier(dateColumn)
                + " <= toDateTime('" + dt + " 23:59:59')");
        return sql;
    }

    /** 把 staging 数据写回目标表（配合 {@link #deleteByDate} 使用）。 */
    public String insertFromSource(String database, String table, String dt) {
        return "INSERT INTO " + database + "." + table + " SELECT * FROM " + sourceOf(database, table, dt);
    }

    /**
     * 分区 ID：与迁移脚本中 {@code PARTITION BY toYYYYMM(dt)} 对应。
     *
     * <p>若后续改为按天分区，这里需要同步调整——因此集中在一处而不是散落在 SQL 里。
     */
    public static String partitionId(String dt) {
        Objects.requireNonNull(dt, "dt");
        return dt.substring(0, 7).replace("-", "");
    }

    /**
     * Spark 写入湖仓后，ClickHouse 读取的源（S3 表函数 + 通配路径）。
     *
     * <p>路径中的 {@code {}} 由 {@code s3()} 支持（Glob 展开）；dt 从文件路径解析，
     * 因为 Spark 的分区列不会写进 parquet 文件内部。
     */
    public static String sourceOf(String database, String table, String dt) {
        return sourceOf("${tmdbwh.s3.lake-url}", database, table, dt);
    }

    /**
     * Spark 写入湖仓后，ClickHouse 读取的源（S3 表函数 + 通配路径）。
     *
     * <p>路径中的 {@code *.parquet} 由 {@code s3()} 做 Glob 展开；dt 不在 parquet 文件内部
     * （Spark 的分区列不写入数据文件），因此由路径中的 {@code dt=} 目录保证只装载目标日期。
     */
    public static String sourceOf(String lakeUrl, String database, String table, String dt) {
        String base = lakeUrl.endsWith("/") ? lakeUrl : lakeUrl + "/";
        return "s3('" + base + database + "." + table + "/dt=" + dt + "/*.parquet'"
                + ", '${tmdbwh.s3.access-key}', '${tmdbwh.s3.secret-key}', 'Parquet')";
    }
}
