package com.tmdbwh.offline;

import com.tmdbwh.common.config.AppConfig;
import com.tmdbwh.common.config.IcebergConfig;
import com.tmdbwh.common.config.S3Config;
import org.apache.spark.sql.SparkSession;

/**
 * SparkSession 构建：统一装配 Iceberg Catalog 与 MinIO / S3 访问。
 *
 * <p>集中在这里的原因：离线作业、Iceberg 维护、图数据导出都需要同一套 Catalog 与对象存储配置，
 * 分散在各自的 main() 里必然会出现"某个作业少配一项导致报 NoSuchBucket / 找不到 catalog"的问题。
 *
 * <p>注意：hive.metastore.uris 与 Iceberg Catalog 无关（Iceberg 用的是自己的 catalog 实现），
 * 但 Spark 的 spark-hive 支持会用到它，因此一并设置。
 */
public final class SparkSupport {

    private SparkSupport() {}

    /** 会话使用的 Catalog 名称。 */
    public static final String CATALOG = "lake";

    /**
     * 构建 SparkSession。
     *
     * @param config 平台配置
     * @param appName 应用名
     * @param local 是否本地模式（单元测试用 local[*]，生产由 spark-submit 指定 master）
     */
    public static SparkSession build(AppConfig config, String appName, boolean local) {
        SparkSession.Builder builder = SparkSession.builder()
                .appName(appName)
                .config("spark.sql.extensions", "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions")
                .config("spark.sql.catalog." + CATALOG, "org.apache.iceberg.spark.SparkCatalog")
                .config("spark.sql.defaultCatalog", CATALOG)
                .config("spark.sql.session.timeZone", "UTC")
                .config("spark.sql.sources.partitionOverwriteMode", "dynamic")
                .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
                // 小文件控制：离线任务每天一批，默认 200 个 shuffle 分区会产生大量小文件
                .config("spark.sql.shuffle.partitions", "8")
                .config("spark.sql.adaptive.enabled", "true");

        IcebergConfig iceberg = config.getIceberg();
        String catalogType = iceberg.getCatalogType() == null || iceberg.getCatalogType().isEmpty()
                ? "hive" : iceberg.getCatalogType();
        if ("hive".equalsIgnoreCase(catalogType)) {
            builder.config("spark.sql.catalog." + CATALOG + ".type", "hive")
                    .config("spark.sql.catalog." + CATALOG + ".uri", config.getIceberg().getMetastoreUri());
        } else {
            builder.config("spark.sql.catalog." + CATALOG + ".type", catalogType);
        }
        builder.config("spark.sql.catalog." + CATALOG + ".warehouse", iceberg.getWarehouse());
        builder.config("spark.hadoop.hive.metastore.uris", config.getIceberg().getMetastoreUri());

        S3Config s3 = config.getS3();
        builder.config("spark.hadoop.fs.s3a.endpoint", s3.getEndpoint())
                .config("spark.hadoop.fs.s3a.access.key", s3.getAccessKey())
                .config("spark.hadoop.fs.s3a.secret.key", s3.getSecretKey())
                .config("spark.hadoop.fs.s3a.path.style.access", String.valueOf(s3.isPathStyleAccess()))
                .config("spark.hadoop.fs.s3a.connection.ssl.enabled", String.valueOf(s3.isSslEnabled()))
                .config("spark.hadoop.fs.s3a.impl", "org.apache.hadoop.fs.s3a.S3AFileSystem")
                // 使用环境变量凭据（容器内由 compose 注入 AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY）
                .config("spark.hadoop.fs.s3a.aws.credentials.provider",
                        "com.amazonaws.auth.EnvironmentVariableCredentialsProvider");

        if (local) {
            builder.master("local[*]")
                    .config("spark.ui.enabled", "false")
                    .config("spark.sql.warehouse.dir", System.getProperty("java.io.tmpdir") + "/tmdbwh-warehouse")
                    // 本地测试不连接 Hive Metastore，使用 Hadoop 类型 Catalog 直接写对象存储/本地目录
                    .config("spark.sql.catalog." + CATALOG, "org.apache.iceberg.spark.SparkCatalog")
                    .config("spark.sql.catalog." + CATALOG + ".type", "hadoop");
        }
        return builder.getOrCreate();
    }
}
