package com.tmdbwh.ingestion.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.tmdbwh.common.config.AppConfig;
import com.tmdbwh.common.json.JsonUtils;
import com.tmdbwh.common.model.EntityType;
import com.tmdbwh.common.storage.CheckpointStore;
import com.tmdbwh.common.storage.ObjectStore;
import com.tmdbwh.ingestion.client.TmdbClient;
import com.tmdbwh.ingestion.metrics.IngestionMetrics;
import com.tmdbwh.ingestion.support.DirObjectStore;
import com.tmdbwh.ingestion.support.MockServers;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.zip.GZIPInputStream;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 全量采集端到端：Mock TMDB → 数据湖原始区。 */
class FullLoadJobTest {

    private static final LocalDate DT = LocalDate.of(2026, 9, 30);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-30T10:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path dir;

    private MockWebServer server;
    private ObjectStore store;
    private IngestionMetrics metrics;
    private TmdbClient client;
    private AppConfig config;

    @BeforeEach
    void setUp() {
        server = new MockWebServer();
        store = new DirObjectStore(dir.resolve("objects"), "tmdb-lake");
        metrics = IngestionMetrics.create();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (client != null) {
            client.close();
        }
        metrics.close();
        server.close();
    }

    private AppConfig config() {
        if (config == null) {
            String base = server.url("/3").toString().replaceAll("/+$", "").replace("localhost", "127.0.0.1");
            config = MockServers.configFor(server, java.util.Map.of("tmdbwh.tmdb.export-base-url", base));
            client = new TmdbClient(config.getTmdb(), metrics);
        }
        return config;
    }

    private void enqueueExport(int count) {
        StringBuilder ndjson = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            ndjson.append("{\"id\":").append(i).append(",\"title\":\"Movie ").append(i)
                    .append("\",\"popularity\":10.0,\"adult\":false}\n");
        }
        server.enqueue(new MockResponse().setBody(ndjson.toString()));
    }

    private void enqueueMovie(int id) {
        server.enqueue(new MockResponse().setBody(
                "{\"id\":" + id + ",\"title\":\"Movie " + id + "\",\"vote_average\":7.5,\"budget\":1000}"));
    }

    private JobResult run(EntityType type, int maxIds, boolean resume) {
        CheckpointStore<FullLoadState> checkpoints =
                new CheckpointStore<>(store, "full_load_" + type.getApiPath());
        FullLoadJob job = new FullLoadJob(config(), client, store, checkpoints, null, metrics, CLOCK);
        return job.run(new FullLoadJob.Options()
                .entityType(type)
                .maxIds(maxIds)
                .exportDate(DT)
                .resume(resume)
                .concurrency(2)
                .checkpointEvery(2));
    }

    private List<String> readLines(String key) throws IOException {
        try (InputStream in = new GZIPInputStream(store.openStream(key))) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return text.isBlank() ? List.of() : List.of(text.split("\n"));
        }
    }

    @Test
    void writesRawRecordsToLakePartition() throws IOException {
        enqueueExport(5);
        for (int i = 1; i <= 5; i++) {
            enqueueMovie(i);
        }

        JobResult result = run(EntityType.MOVIE, 0, true);

        assertThat(result.getTotal()).isEqualTo(5);
        assertThat(result.getSuccess()).isEqualTo(5);
        assertThat(result.getFailed()).isZero();
        assertThat(result.getWrittenKeys()).hasSize(1);
        assertThat(result.getWrittenKeys().get(0))
                .matches("raw/movie/dt=2026-09-30/part-[a-z0-9-]+-\\d{5}\\.ndjson\\.gz");

        List<String> lines = readLines(result.getWrittenKeys().get(0));
        assertThat(lines).hasSize(5);
        JsonNode first = JsonUtils.readTree(lines.get(0));
        assertThat(first.get("entity_type").asText()).isEqualTo("movie");
        assertThat(first.get("dt").asText()).isEqualTo("2026-09-30");
        assertThat(first.get("ingest_time").asLong()).isEqualTo(CLOCK.millis());
        assertThat(first.get("payload").get("title").asText()).startsWith("Movie");
        // 原始报文原样保留，不做任何清洗（清洗属于 DWD 职责）
        assertThat(first.get("schema_version").asInt()).isEqualTo(1);
    }

    @Test
    void maxIdsLimitsSample() throws IOException {
        enqueueExport(5);
        for (int i = 1; i <= 2; i++) {
            enqueueMovie(i);
        }

        JobResult result = run(EntityType.MOVIE, 2, true);

        assertThat(result.getTotal()).isEqualTo(2);
        assertThat(readLines(result.getWrittenKeys().get(0))).hasSize(2);
    }

    @Test
    void missingIdsAreCountedAsNotFoundAndDoNotFailJob() {
        enqueueExport(3);
        server.enqueue(new MockResponse().setBody("{\"id\":1,\"title\":\"Movie 1\"}"));
        server.enqueue(new MockResponse().setResponseCode(404).setBody(MockServers.notFoundBody()));
        server.enqueue(new MockResponse().setBody("{\"id\":3,\"title\":\"Movie 3\"}"));

        JobResult result = run(EntityType.MOVIE, 0, true);

        assertThat(result.getNotFound()).isEqualTo(1);
        assertThat(result.getSuccess()).isEqualTo(2);
        assertThat(result.isFailure(0.05)).isFalse();
    }

    @Test
    void resumesFromCheckpoint() {
        // 预先写入断点：前 2 条已处理
        CheckpointStore<FullLoadState> seed = new CheckpointStore<>(store, "full_load_movie");
        FullLoadState state = new FullLoadState();
        state.setEntityType("movie");
        state.setExportDate("09_30_2026");
        state.setOffset(2);
        state.setRunId("prev-run");
        seed.save(state);

        enqueueExport(4);
        enqueueMovie(3);
        enqueueMovie(4);

        JobResult result = run(EntityType.MOVIE, 0, true);

        // 断点之后只处理剩余 2 条
        assertThat(result.getTotal()).isEqualTo(2);
        assertThat(result.getSuccess()).isEqualTo(2);
    }

    @Test
    void resumeFalseRestartsFromScratch() {
        CheckpointStore<FullLoadState> seed = new CheckpointStore<>(store, "full_load_movie");
        FullLoadState state = new FullLoadState();
        state.setEntityType("movie");
        state.setExportDate("09_30_2026");
        state.setOffset(2);
        seed.save(state);

        enqueueExport(3);
        for (int i = 1; i <= 3; i++) {
            enqueueMovie(i);
        }

        JobResult result = run(EntityType.MOVIE, 0, false);

        assertThat(result.getTotal()).isEqualTo(3);
    }

    @Test
    void completedRunClearsCheckpoint() {
        enqueueExport(2);
        enqueueMovie(1);
        enqueueMovie(2);

        run(EntityType.MOVIE, 0, true);

        CheckpointStore<FullLoadState> checkpoints = new CheckpointStore<>(store, "full_load_movie");
        assertThat(checkpoints.load(FullLoadState.class)).isEmpty();
    }
}
