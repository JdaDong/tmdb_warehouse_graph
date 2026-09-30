package com.tmdbwh.ingestion.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.tmdbwh.common.config.AppConfig;
import com.tmdbwh.common.storage.CheckpointStore;
import com.tmdbwh.common.storage.ObjectStore;
import com.tmdbwh.ingestion.client.TmdbClient;
import com.tmdbwh.ingestion.metrics.IngestionMetrics;
import com.tmdbwh.ingestion.support.DirObjectStore;
import com.tmdbwh.ingestion.support.MockServers;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 增量采集：水位线推进与幂等重放。 */
class IncrementalChangesJobTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-30T10:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate END = LocalDate.of(2026, 9, 30);

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
        String base = server.url("/3").toString().replaceAll("/+$", "").replace("localhost", "127.0.0.1");
        config = MockServers.configFor(server, Map.of("tmdbwh.tmdb.export-base-url", base));
        client = new TmdbClient(config.getTmdb(), metrics);
    }

    @AfterEach
    void tearDown() throws IOException {
        client.close();
        metrics.close();
        server.close();
    }

    private JobResult run(LocalDate endDate, int defaultStartDays, boolean includeAdult) {
        CheckpointStore<IncrementalState> checkpoints = new CheckpointStore<>(store, "incremental_changes");
        IncrementalChangesJob job = new IncrementalChangesJob(config, client, store, checkpoints, null, metrics, CLOCK);
        return job.run(new IncrementalChangesJob.Options()
                .entityTypes(List.of(com.tmdbwh.common.model.EntityType.MOVIE))
                .endDate(endDate)
                .defaultStartDays(defaultStartDays)
                .includeAdult(includeAdult)
                .emitKafka(false));
    }

    @Test
    void firstRunUsesLookbackThenAdvancesWatermark() {
        // 无水位线：从 endDate - 0 天开始（defaultStartDays=0），只查 1 个窗口
        server.enqueue(new MockResponse().setBody(MockServers.page(1, 1,
                "[{\"id\":27205,\"adult\":false},{\"id\":155,\"adult\":false}]")));
        server.enqueue(new MockResponse().setBody("{\"id\":27205,\"title\":\"Inception\"}"));
        server.enqueue(new MockResponse().setBody("{\"id\":155,\"title\":\"The Dark Knight\"}"));

        JobResult result = run(END, 0, false);

        assertThat(result.getTotal()).isEqualTo(2);
        assertThat(result.getSuccess()).isEqualTo(2);
        CheckpointStore<IncrementalState> checkpoints = new CheckpointStore<>(store, "incremental_changes");
        IncrementalState state = checkpoints.load(IncrementalState.class).orElseThrow();
        // 窗口处理成功后水位线推进到 endDate + 1 天
        assertThat(state.watermarkOf("movie")).isEqualTo(END.plusDays(1));
    }

    @Test
    void duplicatesWithinWindowAreProcessedOnce() {
        server.enqueue(new MockResponse().setBody(MockServers.page(1, 1,
                "[{\"id\":27205,\"adult\":false},{\"id\":27205,\"adult\":false}]")));
        server.enqueue(new MockResponse().setBody("{\"id\":27205,\"title\":\"Inception\"}"));

        JobResult result = run(END, 0, false);

        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getSuccess()).isEqualTo(1);
    }

    @Test
    void adultItemsAreSkippedByDefault() {
        server.enqueue(new MockResponse().setBody(MockServers.page(1, 1, "[{\"id\":999999902,\"adult\":true}]")));

        JobResult skipped = run(END, 0, false);

        // adult 项在"尝试采集"之前就被过滤，因此不计入 total / notFound
        assertThat(skipped.getTotal()).isZero();
        assertThat(skipped.getNotFound()).isZero();
    }

    @Test
    void adultItemsAreIncludedWhenRequested() {
        server.enqueue(new MockResponse().setBody(MockServers.page(1, 1, "[{\"id\":999999902,\"adult\":true}]")));
        server.enqueue(new MockResponse().setBody("{\"id\":999999902,\"title\":\"Adult Movie\"}"));

        JobResult included = run(END, 0, true);

        assertThat(included.getTotal()).isEqualTo(1);
        assertThat(included.getSuccess()).isEqualTo(1);
    }

    @Test
    void watermarkIsNotAdvancedWhenWindowFails() {
        // 水位线设为 endDate - 3 天，本次需处理 [endDate-3, endDate] 这一窗口
        CheckpointStore<IncrementalState> seed = new CheckpointStore<>(store, "incremental_changes");
        IncrementalState initial = new IncrementalState();
        initial.setWatermark("movie", END.minusDays(3));
        seed.save(initial);

        // changes 接口持续 500（默认 5 次重试全部失败）
        for (int i = 0; i < 5; i++) {
            server.enqueue(new MockResponse().setResponseCode(500).setBody("{\"status_code\":2}"));
        }
        JobResult failed = run(END, 0, false);

        assertThat(failed.getTotal()).isZero();
        CheckpointStore<IncrementalState> checkpoints = new CheckpointStore<>(store, "incremental_changes");
        assertThat(checkpoints.load(IncrementalState.class).orElseThrow().watermarkOf("movie"))
                .as("窗口失败时水位线必须保持不变，下次运行重放该窗口")
                .isEqualTo(END.minusDays(3));
    }

    @Test
    void laterWatermarkIsNeverRewound() {
        CheckpointStore<IncrementalState> seed = new CheckpointStore<>(store, "incremental_changes");
        IncrementalState state = new IncrementalState();
        state.setWatermark("movie", END.plusDays(5));
        seed.save(state);

        JobResult result = run(END, 3, false);

        // 水位线已超过目标日期，直接跳过且不回退
        assertThat(result.getTotal()).isZero();
        CheckpointStore<IncrementalState> checkpoints = new CheckpointStore<>(store, "incremental_changes");
        assertThat(checkpoints.load(IncrementalState.class).orElseThrow().watermarkOf("movie"))
                .isEqualTo(END.plusDays(5));
    }
}
