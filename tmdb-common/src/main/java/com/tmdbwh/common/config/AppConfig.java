package com.tmdbwh.common.config;

import com.tmdbwh.common.exception.InvalidConfigurationException;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;
import java.io.File;
import java.io.Serializable;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 平台类型化配置入口。
 *
 * <p>加载规则见 {@code reference.conf} 顶部说明。构造时会做两级校验并<b>快速失败</b>：
 *
 * <ol>
 *   <li>结构校验：键是否存在、类型是否正确（基于 reference.conf 进行 checkValid）；
 *   <li>语义校验：URL 协议、正数、命名规范等，一次性汇总全部错误后抛出 {@link InvalidConfigurationException}。
 * </ol>
 *
 * <p>凭证类必填项（如 TMDB Token）不在此处强制，由真正使用它的组件调用 {@link TmdbConfig#requireCredentials()}， 这样治理 / 图谱等不需要 TMDB 的
 * CLI 也能正常启动。
 *
 * <p>本类及各配置段均为不可变且可序列化，可安全地随 Flink / Spark 算子分发到 TaskManager / Executor。
 */
public final class AppConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 配置根路径。 */
    public static final String ROOT = "tmdbwh";

    private final String env;
    private final ZoneId businessZone;
    private final TmdbConfig tmdb;
    private final S3Config s3;
    private final KafkaConfig kafka;
    private final ClickHouseConfig clickhouse;
    private final Neo4jConfig neo4j;
    private final IcebergConfig iceberg;

    private AppConfig(Config root) {
        Config c = root.getConfig(ROOT);
        List<String> errors = new ArrayList<>();
        this.env = c.getString("env");
        this.businessZone = parseZone(c.getString("business-zone"), errors);
        this.tmdb = TmdbConfig.from(c.getConfig("tmdb"));
        this.s3 = S3Config.from(c.getConfig("s3"));
        this.kafka = KafkaConfig.from(c.getConfig("kafka"));
        this.clickhouse = ClickHouseConfig.from(c.getConfig("clickhouse"));
        this.neo4j = Neo4jConfig.from(c.getConfig("neo4j"));
        this.iceberg = IcebergConfig.from(c.getConfig("iceberg"));

        ConfigSupport.requireNonBlank(errors, "tmdbwh.env", env);
        tmdb.validate(errors);
        s3.validate(errors);
        kafka.validate(errors);
        clickhouse.validate(errors);
        neo4j.validate(errors);
        iceberg.validate(errors);
        if (!errors.isEmpty()) {
            throw new InvalidConfigurationException(errors);
        }
    }

    /** 按标准规则加载（系统属性 &gt; -Dconfig.file &gt; application.conf &gt; reference.conf）。 */
    public static AppConfig load() {
        ConfigFactory.invalidateCaches();
        return from(ConfigFactory.load());
    }

    /**
     * 加载指定文件并以标准配置兜底；用于 CLI 的 {@code --config} 参数。
     *
     * @param file HOCON 配置文件
     */
    public static AppConfig load(File file) {
        Objects.requireNonNull(file, "file");
        if (!file.isFile()) {
            throw new InvalidConfigurationException("配置文件不存在: " + file.getAbsolutePath());
        }
        ConfigFactory.invalidateCaches();
        Config fileConfig = ConfigFactory.parseFile(file);
        Config merged = ConfigFactory.defaultOverrides()
                .withFallback(fileConfig)
                .withFallback(ConfigFactory.defaultApplication())
                .withFallback(ConfigFactory.defaultReference())
                .resolve();
        return from(merged);
    }

    /**
     * 从已构建好的 Config 创建（测试中常用 {@code ConfigFactory.parseMap(...).withFallback(defaultReference())}）。
     *
     * @throws InvalidConfigurationException 结构或语义校验失败
     */
    public static AppConfig from(Config config) {
        Config resolved = config.resolve();
        try {
            resolved.checkValid(ConfigFactory.defaultReference(), ROOT);
            return new AppConfig(resolved);
        } catch (ConfigException e) {
            throw new InvalidConfigurationException("配置结构错误: " + e.getMessage());
        }
    }

    private static ZoneId parseZone(String zone, List<String> errors) {
        try {
            return ZoneId.of(zone);
        } catch (DateTimeException e) {
            errors.add("tmdbwh.business-zone 不是合法时区: " + zone);
            return ZoneId.of("UTC");
        }
    }

    public String getEnv() {
        return env;
    }

    public ZoneId getBusinessZone() {
        return businessZone;
    }

    public TmdbConfig getTmdb() {
        return tmdb;
    }

    public S3Config getS3() {
        return s3;
    }

    public KafkaConfig getKafka() {
        return kafka;
    }

    public ClickHouseConfig getClickhouse() {
        return clickhouse;
    }

    public Neo4jConfig getNeo4j() {
        return neo4j;
    }

    public IcebergConfig getIceberg() {
        return iceberg;
    }

    /** 输出脱敏后的配置摘要，适合在作业启动日志中打印。 */
    @Override
    public String toString() {
        return "AppConfig{env=" + env + ", businessZone=" + businessZone + ",\n  " + tmdb + ",\n  " + s3 + ",\n  "
                + kafka + ",\n  " + clickhouse + ",\n  " + neo4j + ",\n  " + iceberg + "\n}";
    }
}
