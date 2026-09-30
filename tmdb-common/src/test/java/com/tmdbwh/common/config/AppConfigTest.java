package com.tmdbwh.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tmdbwh.common.exception.InvalidConfigurationException;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppConfigTest {

    private static AppConfig withOverrides(Map<String, Object> overrides) {
        Config config = ConfigFactory.parseMap(overrides).withFallback(ConfigFactory.defaultReference());
        return AppConfig.from(config);
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void referenceDefaultsAreValidAndTyped() {
        AppConfig cfg = AppConfig.from(ConfigFactory.defaultReference());

        assertThat(cfg.getBusinessZone()).isEqualTo(ZoneId.of("UTC"));
        assertThat(cfg.getTmdb().getBaseUrl()).isEqualTo("https://api.themoviedb.org/3");
        assertThat(cfg.getTmdb().getRateLimitPerSecond()).isEqualTo(40);
        assertThat(cfg.getTmdb().getReadTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(cfg.getS3().getBucket()).isEqualTo("tmdb-lake");
        assertThat(cfg.getS3().isPathStyleAccess()).isTrue();
        assertThat(cfg.getKafka().getEntityChangeTopic()).isEqualTo("tmdb.entity.change");
        assertThat(cfg.getKafka().getDlqTopic()).isEqualTo("tmdb.dlq");
        assertThat(cfg.getClickhouse().isClustered()).isFalse();
        assertThat(cfg.getClickhouse().getBatchSize()).isEqualTo(10000);
        assertThat(cfg.getNeo4j().getBatchSize()).isEqualTo(5000);
        assertThat(cfg.getIceberg().getCatalogType()).isEqualTo("hive");
    }

    @Test
    void overridesTakePrecedenceAndTrailingSlashIsStripped() {
        AppConfig cfg = withOverrides(map(
                "tmdbwh.tmdb.base-url", "http://wiremock:8080/3/",
                "tmdbwh.tmdb.rate-limit-per-second", 5,
                "tmdbwh.clickhouse.cluster", "tmdb_cluster",
                "tmdbwh.business-zone", "Asia/Shanghai"));

        assertThat(cfg.getTmdb().getBaseUrl()).isEqualTo("http://wiremock:8080/3");
        assertThat(cfg.getTmdb().getRateLimitPerSecond()).isEqualTo(5);
        assertThat(cfg.getClickhouse().isClustered()).isTrue();
        assertThat(cfg.getBusinessZone()).isEqualTo(ZoneId.of("Asia/Shanghai"));
    }

    @Test
    void collectsAllSemanticErrorsAtOnce() {
        assertThatThrownBy(() -> withOverrides(map(
                "tmdbwh.tmdb.base-url", "ftp://bad",
                "tmdbwh.tmdb.rate-limit-per-second", 0,
                "tmdbwh.s3.bucket", "Bad_Bucket",
                "tmdbwh.clickhouse.url", "http://localhost:8123",
                "tmdbwh.neo4j.uri", "http://neo4j:7474",
                "tmdbwh.business-zone", "Mars/Olympus")))
                .isInstanceOf(InvalidConfigurationException.class)
                .satisfies(e -> {
                    InvalidConfigurationException ice = (InvalidConfigurationException) e;
                    assertThat(ice.getErrors()).hasSize(6);
                    assertThat(ice.getMessage())
                            .contains("tmdbwh.tmdb.base-url")
                            .contains("rate-limit-per-second")
                            .contains("tmdbwh.s3.bucket")
                            .contains("tmdbwh.clickhouse.url")
                            .contains("tmdbwh.neo4j.uri")
                            .contains("business-zone");
                });
    }

    @Test
    void wrongTypeIsReportedAsStructureError() {
        assertThatThrownBy(() -> withOverrides(map("tmdbwh.tmdb.max-concurrency", "many")))
                .isInstanceOf(InvalidConfigurationException.class)
                .hasMessageContaining("配置结构错误")
                .hasMessageContaining("max-concurrency");
    }

    @Test
    void credentialsRequiredOnlyWhenNotMockMode() {
        AppConfig noCreds = withOverrides(map("tmdbwh.tmdb.bearer-token", "", "tmdbwh.tmdb.api-key", ""));
        assertThatThrownBy(() -> noCreds.getTmdb().requireCredentials())
                .isInstanceOf(InvalidConfigurationException.class)
                .hasMessageContaining("TMDB_BEARER_TOKEN");

        AppConfig mock = withOverrides(map("tmdbwh.tmdb.mock-mode", true));
        assertThatCode(() -> mock.getTmdb().requireCredentials()).doesNotThrowAnyException();

        AppConfig bearer = withOverrides(map("tmdbwh.tmdb.bearer-token", "  eyJhbGciOiJIUzI1NiJ9.abc.def  "));
        assertThat(bearer.getTmdb().useBearerAuth()).isTrue();
        assertThat(bearer.getTmdb().getBearerToken()).isEqualTo("eyJhbGciOiJIUzI1NiJ9.abc.def");

        AppConfig apiKey = withOverrides(map("tmdbwh.tmdb.api-key", "0123456789abcdef"));
        assertThat(apiKey.getTmdb().useBearerAuth()).isFalse();
        assertThat(apiKey.getTmdb().hasCredentials()).isTrue();
    }

    @Test
    void toStringNeverLeaksSecrets() {
        AppConfig cfg = withOverrides(map(
                "tmdbwh.tmdb.bearer-token", "super-secret-bearer-token-value",
                "tmdbwh.s3.secret-key", "minio-secret-key-123456",
                "tmdbwh.clickhouse.password", "ch-password-123456",
                "tmdbwh.neo4j.password", "neo4j-password-123456",
                "tmdbwh.kafka.properties.\"sasl.jaas.config\"", "org.apache.kafka...password=\"x\""));

        String printed = cfg.toString();
        assertThat(printed)
                .doesNotContain("super-secret-bearer-token-value")
                .doesNotContain("minio-secret-key-123456")
                .doesNotContain("ch-password-123456")
                .doesNotContain("neo4j-password-123456")
                .doesNotContain("password=\"x\"")
                .contains("supe****alue");
    }

    @Test
    void kafkaPassThroughPropertiesUseNativeDottedKeys() {
        Config config = ConfigFactory.parseString(
                        "tmdbwh.kafka.properties { \"security.protocol\" = SASL_SSL, sasl.mechanism = PLAIN }")
                .withFallback(ConfigFactory.defaultReference());
        KafkaConfig kafka = AppConfig.from(config).getKafka();

        assertThat(kafka.getProperties())
                .containsEntry("security.protocol", "SASL_SSL")
                .containsEntry("sasl.mechanism", "PLAIN");
        assertThat(kafka.toClientProperties("ingestion-changes"))
                .containsEntry("bootstrap.servers", "localhost:9092")
                .containsEntry("client.id", "tmdbwh-ingestion-changes")
                .containsEntry("security.protocol", "SASL_SSL");
    }

    @Test
    void loadFromFileFallsBackToReference(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("app.conf");
        Files.write(file, "tmdbwh { env = \"staging\", neo4j.batch-size = 123 }".getBytes(StandardCharsets.UTF_8));

        AppConfig cfg = AppConfig.load(file.toFile());

        assertThat(cfg.getNeo4j().getBatchSize()).isEqualTo(123);
        assertThat(cfg.getS3().getBucket()).isEqualTo("tmdb-lake");
    }

    @Test
    void loadFromMissingFileFailsFast() {
        assertThatThrownBy(() -> AppConfig.load(new File("/definitely/not/here.conf")))
                .isInstanceOf(InvalidConfigurationException.class)
                .hasMessageContaining("配置文件不存在");
    }

    @Test
    void systemPropertiesOverrideDefaults() {
        String key = "tmdbwh.neo4j.batch-size";
        String previous = System.getProperty(key);
        System.setProperty(key, "777");
        try {
            assertThat(AppConfig.load().getNeo4j().getBatchSize()).isEqualTo(777);
        } finally {
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        }
    }

    @Test
    void configIsJavaSerializableForDistributedOperators() throws Exception {
        AppConfig cfg = withOverrides(map("tmdbwh.env", "prod"));

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bos)) {
            out.writeObject(cfg);
        }
        AppConfig copy;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bos.toByteArray()))) {
            copy = (AppConfig) in.readObject();
        }
        assertThat(copy.getEnv()).isEqualTo("prod");
        assertThat(copy.getKafka().getPopularityTopic()).isEqualTo(cfg.getKafka().getPopularityTopic());
        assertThat(copy.getTmdb().getReadTimeout()).isEqualTo(cfg.getTmdb().getReadTimeout());
    }
}
