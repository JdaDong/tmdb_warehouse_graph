package com.tmdbwh.offline;

import com.tmdbwh.common.model.EntityType;
import com.tmdbwh.common.storage.LakePaths;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 读取贴源层（ODS）：{@code raw/{entity}/dt=yyyy-MM-dd/*.ndjson.gz}。
 *
 * <p>要点：
 *
 * <ul>
 *   <li>使用显式 Schema 读取 NDJSON（gzip 由 Hadoop 按扩展名自动解压），避免结构推断带来的漂移；
 *   <li>路径由 {@link LakePaths} 统一生成，作业不拼接字符串；
 *   <li><b>分区缺失时返回空 Dataset 而不是报错</b>：某天没有增量变更是正常现象，
 *       下游 transform 必须能处理空输入（"今天没有变更"不应让整条链路失败）。
 * </ul>
 */
public final class OdsReader {

    private static final Logger LOG = LoggerFactory.getLogger(OdsReader.class);

    /** ODS 记录的外层结构（与采集端 RawRecord 一致）。 */
    public static final StructType RAW_SCHEMA = new StructType()
            .add("schema_version", DataTypes.IntegerType)
            .add("entity_type", DataTypes.StringType)
            .add("entity_id", DataTypes.LongType)
            .add("dt", DataTypes.StringType)
            .add("ingest_time", DataTypes.LongType)
            .add("source", DataTypes.StringType)
            .add("payload", DataTypes.StringType);

    private OdsReader() {}

    /** 贴源分区目录（与采集端 LakePaths.rawPartition 对齐）。 */
    private static String rawPartition(EntityType type, String dt) {
        return LakePaths.rawPartition(type.getApiPath(), java.time.LocalDate.parse(dt));
    }

    /**
     * 读取某个实体、某天的原始记录。
     *
     * @param warehouse 湖仓根地址（如 s3a://tmdb-lake/warehouse 或本地目录）
     * @return 记录 Dataset；分区不存在时为空（Schema 仍为 RAW_SCHEMA）
     */
    public static Dataset<Row> read(SparkSession spark, EntityType type, String dt, String warehouse) {
        Objects.requireNonNull(spark, "spark");
        Objects.requireNonNull(type, "type");
        String uri = LakePaths.s3a(warehouse, rawPartition(type, dt));
        List<String> files = listFiles(spark, uri);
        if (files.isEmpty()) {
            LOG.warn("贴源分区不存在，返回空数据集: {}", uri);
            return spark.createDataFrame(Collections.emptyList(), RAW_SCHEMA);
        }
        LOG.info("读取贴源分区: {}（{} 个文件）", uri, files.size());
        return spark.read()
                .schema(RAW_SCHEMA)
                .option("mode", "PERMISSIVE")
                .option("columnNameOfCorruptRecord", "_corrupt_record")
                .json(uri)
                .filter(org.apache.spark.sql.functions.col("entity_id").isNotNull());
    }

    /** 判断贴源分区是否有数据文件。 */
    public static boolean exists(SparkSession spark, EntityType type, String dt) {
        String warehouse = warehouseOf(spark);
        return !listFiles(spark, LakePaths.s3a(warehouse, rawPartition(type, dt))).isEmpty();
    }

    /**
     * 列出分区下的数据文件（*.ndjson.gz）。
     *
     * <p>用 Hadoop FileSystem 的 glob 而不是 SparkContext 的 wholeTextFiles：
     * 后者会把文件内容读进内存（这里只关心"有没有文件"），在对象存储上代价很高。
     */
    public static List<String> listFiles(SparkSession spark, String uri) {
        Objects.requireNonNull(uri, "uri");
        List<String> result = new ArrayList<>();
        try {
            Path dir = new Path(URI.create(uri.endsWith("/") ? uri : uri + "/"));
            FileSystem fs = FileSystem.get(dir.toUri(), new Configuration(spark.sparkContext().hadoopConfiguration()));
            if (!fs.exists(dir)) {
                return result;
            }
            FileStatus[] statuses = fs.globStatus(new Path(dir, "*.ndjson.gz"));
            if (statuses != null) {
                for (FileStatus status : statuses) {
                    result.add(status.getPath().toString());
                }
            }
        } catch (IOException e) {
            LOG.warn("列出贴源文件失败（按空处理）: {} | {}", uri, e.toString());
        }
        return result;
    }

    /** 从当前会话读取湖仓根路径（Iceberg catalog 的 warehouse）。 */
    public static String warehouseOf(SparkSession spark) {
        String warehouse = spark.conf().get("spark.sql.catalog.lake.warehouse", "");
        if (warehouse.isEmpty()) {
            throw new IllegalStateException("未配置 spark.sql.catalog.lake.warehouse，无法确定湖仓根路径");
        }
        return warehouse;
    }
}
