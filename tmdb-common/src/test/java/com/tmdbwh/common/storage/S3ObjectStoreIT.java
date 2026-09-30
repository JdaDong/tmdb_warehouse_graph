package com.tmdbwh.common.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.tmdbwh.common.config.S3Config;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 基于真实 MinIO 的集成测试（mvn verify -Pit，无 Docker 时自动跳过）。
 *
 * <p>MinIO 官方已停止发布社区版镜像（Docker Hub 上的 minio/minio 已下线），这里与 Compose 环境保持一致， 使用冻结版本的
 * {@code bitnamilegacy/minio}；可通过系统属性 {@code -Dtmdbwh.it.minio.image=...} 替换。
 */
@Testcontainers(disabledWithoutDocker = true)
class S3ObjectStoreIT {

    private static final String IMAGE = System.getProperty("tmdbwh.it.minio.image", "bitnamilegacy/minio:2024.5.10");
    private static final String USER = "it-admin";
    private static final String PASSWORD = "it-admin-secret";
    private static final int API_PORT = 9000;

    @Container
    static final GenericContainer<?> MINIO = new GenericContainer<>(IMAGE)
            .withEnv("MINIO_ROOT_USER", USER)
            .withEnv("MINIO_ROOT_PASSWORD", PASSWORD)
            .withExposedPorts(API_PORT)
            .waitingFor(Wait.forHttp("/minio/health/live").forPort(API_PORT)
                    .withStartupTimeout(Duration.ofMinutes(2)));

    private static S3ObjectStore store;

    @BeforeAll
    static void init() {
        String endpoint = "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(API_PORT);
        S3Config cfg = new S3Config(endpoint, "us-east-1", USER, PASSWORD, "tmdb-lake-it", true);
        store = S3ObjectStore.create(cfg);
        store.ensureBucket();
        store.ensureBucket();
    }

    @AfterAll
    static void close() {
        if (store != null) {
            store.close();
        }
    }

    @Test
    void stateFileLifecycle() {
        String key = LakePaths.state("it_job");
        assertThat(store.exists(key)).isFalse();
        assertThat(store.getString(key)).isEmpty();

        store.putJson(key, "{\"cursor\":1}");
        store.putJson(key, "{\"cursor\":2}");

        assertThat(store.exists(key)).isTrue();
        assertThat(store.getString(key)).contains("{\"cursor\":2}");
        store.delete(key);
        assertThat(store.exists(key)).isFalse();
    }

    @Test
    void listStreamAndDeletePartition() throws Exception {
        LocalDate dt = LocalDate.of(2026, 9, 30);
        for (int i = 0; i < 5; i++) {
            store.putBytes(LakePaths.rawFile("movie", dt, "it", i), ("line" + i).getBytes(StandardCharsets.UTF_8),
                    "application/gzip");
        }
        String prefix = LakePaths.rawPartition("movie", dt);

        assertThat(store.listKeys(prefix)).hasSize(5).allMatch(k -> k.startsWith(prefix));
        try (InputStream in = store.openStream(LakePaths.rawFile("movie", dt, "it", 3))) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("line3");
        }
        assertThat(store.deletePrefix(prefix)).isEqualTo(5);
        assertThat(store.listKeys(prefix)).isEmpty();
    }
}
