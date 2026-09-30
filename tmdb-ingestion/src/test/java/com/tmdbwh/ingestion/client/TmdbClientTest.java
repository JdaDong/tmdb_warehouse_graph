package com.tmdbwh.ingestion.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.tmdbwh.common.config.AppConfig;
import com.tmdbwh.common.config.TmdbConfig;
import com.tmdbwh.common.model.ChangeItem;
import com.tmdbwh.common.model.EntityType;
import com.tmdbwh.common.model.PagedResponse;
import com.tmdbwh.common.util.Masking;
import com.tmdbwh.ingestion.metrics.IngestionMetrics;
import com.tmdbwh.ingestion.support.MockServers;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TmdbClientTest {

    private MockWebServer server;
    private IngestionMetrics metrics;
    private TmdbClient client;

    @BeforeEach
    void setUp() {
        server = new MockWebServer();
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

    private TmdbClient newClient(Map<String, Object> extra) {
        AppConfig config = MockServers.configFor(server, extra);
        client = new TmdbClient(config.getTmdb(), metrics);
        return client;
    }

    private TmdbClient newClient() {
        return newClient(null);
    }

    @Test
    void getMovieParsesDetailsAndUsesBearerAuth() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"id\":27205,\"title\":\"Inception\",\"vote_average\":8.369}"));

        Optional<com.tmdbwh.common.model.Movie> movie = newClient().getMovie(27205L);

        assertThat(movie).isPresent();
        assertThat(movie.get().getTitle()).isEqualTo("Inception");
        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).startsWith("/3/movie/27205");
        assertThat(request.getPath()).doesNotContain("/3/3/");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer test-bearer-token-value");
        // 详情默认附带子资源，把 3~4 次请求合并为 1 次
        assertThat(request.getPath()).contains("append_to_response=credits%2Ckeywords%2Crelease_dates");
        assertThat(request.getPath()).contains("language=en-US");
        assertThat(metrics.requestCount()).isEqualTo(1);
    }

    @Test
    void apiKeyIsUsedWhenNoBearerToken() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"id\":1,\"name\":\"x\"}"));
        AppConfig config = MockServers.configFor(server, Map.of(
                "tmdbwh.tmdb.bearer-token", "",
                "tmdbwh.tmdb.api-key", "v3-api-key-abcdef"));
        client = new TmdbClient(config.getTmdb(), metrics);

        client.getTvShow(1399L);

        RecordedRequest request = server.takeRequest();
        assertThat(request.getHeader("Authorization")).isNull();
        assertThat(request.getPath()).contains("api_key=v3-api-key-abcdef");
        assertThat(config.getTmdb().useBearerAuth()).isFalse();
    }

    @Test
    void notFoundReturnsEmptyForDetails() {
        server.enqueue(new MockResponse().setResponseCode(404).setBody(MockServers.notFoundBody()));
        server.enqueue(new MockResponse().setResponseCode(404).setBody(MockServers.notFoundBody()));

        TmdbClient c = newClient();
        assertThat(c.getDetails(EntityType.MOVIE, 999999901L, false)).isEmpty();
        assertThat(c.getMovie(999999901L)).isEmpty();
        assertThat(metrics.notFoundCount()).isEqualTo(2);
    }

    @Test
    void rateLimitedRequestIsRetriedAfterRetryAfterHeader() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(429)
                .setHeader("Retry-After", "1")
                .setBody("{\"status_code\":25}"));
        server.enqueue(new MockResponse().setBody("{\"id\":27205,\"title\":\"Inception\"}"));

        long start = System.currentTimeMillis();
        Optional<JsonNode> node = newClient().getDetails(EntityType.MOVIE, 27205L, false);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(node).isPresent();
        assertThat(node.get().get("title").asText()).isEqualTo("Inception");
        // 遵守服务端建议的等待时间（1s），而不是继续猛打
        assertThat(elapsed).isGreaterThanOrEqualTo(1000L);
        assertThat(metrics.rateLimitedCount()).isEqualTo(1);
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void serverErrorIsRetriedUntilExhausted() {
        for (int i = 0; i < 3; i++) {
            server.enqueue(new MockResponse().setResponseCode(503).setBody("{\"status_code\":2}"));
        }
        TmdbClient c = newClient(Map.of("tmdbwh.tmdb.max-retries", 3));

        assertThatThrownBy(() -> c.getDetails(EntityType.MOVIE, 27205L, false))
                .isInstanceOf(TmdbServerException.class)
                .hasMessageContaining("503");
        assertThat(server.getRequestCount()).isEqualTo(3);
    }

    @Test
    void clientErrorFailsImmediatelyWithoutRetry() {
        server.enqueue(new MockResponse().setResponseCode(401).setBody("{\"status_code\":7}"));

        assertThatThrownBy(() -> newClient().getDetails(EntityType.MOVIE, 27205L, false))
                .isInstanceOf(TmdbClientException.class)
                .hasMessageContaining("401");
        // 4xx 重试无意义，只发一次
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void ioFailureIsRetriedAndCounted() {
        // 首次不响应（触发读超时），第二次成功；读超时设短以保证测试快速完成
        server.enqueue(new MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE));
        server.enqueue(new MockResponse().setBody("{\"id\":1}"));

        Optional<JsonNode> node = newClient(Map.of(
                "tmdbwh.tmdb.read-timeout", "300ms",
                "tmdbwh.tmdb.max-retries", 2)).getDetails(EntityType.PERSON, 1L, false);

        assertThat(node).isPresent();
        assertThat(metrics.failureCount()).isEqualTo(1);
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void exceptionMessageMasksSecrets() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));
        AppConfig config = MockServers.configFor(server, Map.of(
                "tmdbwh.tmdb.bearer-token", "",
                "tmdbwh.tmdb.api-key", "api_key_value_123"));
        client = new TmdbClient(config.getTmdb(), metrics);

        assertThatThrownBy(() -> client.getDetails(EntityType.MOVIE, 1L, false))
                .isInstanceOf(TmdbException.class)
                .satisfies(e -> {
                    TmdbException te = (TmdbException) e;
                    assertThat(te.getMessage()).doesNotContain("api_key_value_123");
                    assertThat(te.getUrl()).contains("api_key_value_123");
                });
    }

    @Test
    void changesRequestCarriesDateWindowAndParsesPage() throws InterruptedException {
        server.enqueue(new MockResponse().setBody(
                MockServers.page(1, 2, "[{\"id\":27205,\"adult\":false},{\"id\":155,\"adult\":null}]")));

        TmdbClient c = newClient();
        PagedResponse<ChangeItem> page1 = c.getChanges(EntityType.MOVIE, "2026-09-01", "2026-09-14", 1);
        assertThat(page1.getPage()).isEqualTo(1);
        assertThat(page1.hasNext()).isTrue();
        assertThat(page1.getResults()).extracting(ChangeItem::getId).containsExactly(27205L, 155L);
        assertThat(page1.getResults().get(1).getAdult()).isNull();

        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath())
                .as("changes 请求必须带上查询窗口与页码")
                .contains("start_date=2026-09-01").contains("end_date=2026-09-14").contains("page=1");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void getAllChangesFollowsPagesAndStopsAtLastPage() {
        server.enqueue(new MockResponse().setBody(
                MockServers.page(1, 2, "[{\"id\":27205,\"adult\":false},{\"id\":155,\"adult\":null}]")));
        server.enqueue(new MockResponse().setBody(MockServers.page(2, 2, "[{\"id\":603,\"adult\":false}]")));

        List<ChangeItem> all = newClient().getAllChanges(EntityType.MOVIE, "2026-09-01", "2026-09-14", 10);

        assertThat(all).extracting(ChangeItem::getId)
                .as("应自动翻页并合并两页结果")
                .containsExactly(27205L, 155L, 603L);
        assertThat(server.getRequestCount()).as("末页后不再继续翻页").isEqualTo(2);
    }

    @Test
    void streamListFollowsPages() {
        // 第 1 页声明 total_pages=2 -> 继续翻页；第 2 页为末页 -> 遍历结束
        server.enqueue(new MockResponse().setBody(MockServers.page(1, 2,
                "[{\"id\":1,\"popularity\":10.5},{\"id\":2,\"popularity\":9.1}]")));
        server.enqueue(new MockResponse().setBody(MockServers.page(2, 2, "[{\"id\":3,\"popularity\":8.0}]")));

        // 传入的路径与 TmdbEndpoints 一致（/3 前缀来自 base-url，不在这里重复）
        TmdbClient c = newClient();
        List<Long> ids = c.streamList("/trending/movie/day", 5)
                .map(n -> n.get("id").asLong())
                .collect(java.util.stream.Collectors.toList());

        assertThat(ids).containsExactly(1L, 2L, 3L);
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void streamListStopsAtMaxPages() {
        for (int i = 1; i <= 4; i++) {
            server.enqueue(new MockResponse().setBody(MockServers.page(i, 4, "[{\"id\":" + i + "}]")));
        }
        long count = newClient().streamList("/movie/popular", 2).count();
        assertThat(count).isEqualTo(2);
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void rateLimiterIsConfiguredFromConfig() {
        TmdbClient c = newClient(Map.of("tmdbwh.tmdb.rate-limit-per-second", 7));
        assertThat(c.rateLimiter().getRateLimiterConfig().getLimitForPeriod()).isEqualTo(7);
        assertThat(c.rateLimiter().getRateLimiterConfig().getLimitRefreshPeriod()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void exportNotReadyGivesActionableError() {
        server.enqueue(new MockResponse().setResponseCode(403).setBody("<Error>AccessDenied</Error>"));

        assertThatThrownBy(() -> newClient().downloadExport(
                okhttp3.HttpUrl.parse(server.url("/p/exports/movie_ids_09_30_2026.json.gz").toString())))
                .isInstanceOf(TmdbClient.ExportNotReadyException.class)
                .hasMessageContaining("尚未生成");
    }

    @Test
    void credentialsAreMaskedInConfigOutput() {
        TmdbConfig cfg = MockServers.configFor(server).getTmdb();
        assertThat(cfg.toString()).doesNotContain("test-bearer-token-value");
        assertThat(Masking.maskUrl(server.url("/3/movie/1?api_key=secret").toString())).doesNotContain("secret");
    }
}
