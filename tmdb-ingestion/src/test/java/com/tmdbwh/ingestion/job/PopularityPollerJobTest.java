package com.tmdbwh.ingestion.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.tmdbwh.common.config.AppConfig;
import com.tmdbwh.common.json.JsonUtils;
import com.tmdbwh.common.model.EntityType;
import com.tmdbwh.common.model.EventEnvelope;
import com.tmdbwh.common.model.EventType;
import com.tmdbwh.common.model.PopularityEvent;
import com.tmdbwh.common.storage.ObjectStore;
import com.tmdbwh.ingestion.client.TmdbClient;
import com.tmdbwh.ingestion.metrics.IngestionMetrics;
import com.tmdbwh.ingestion.sink.KafkaEventProducer;
import com.tmdbwh.ingestion.support.DirObjectStore;
import com.tmdbwh.ingestion.support.MockServers;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 热度轮询：榜单 → 事件（名次、事件时间、分区键）。 */
class PopularityPollerJobTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-30T10:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path dir;

    private MockWebServer server;
    private ObjectStore store;
    private IngestionMetrics metrics;
    private TmdbClient client;
    private AppConfig config;
    private MockProducer<String, byte[]> mock;
    private KafkaEventProducer producer;

    @BeforeEach
    void setUp() {
        server = new MockWebServer();
        store = new DirObjectStore(dir.resolve("objects"), "tmdb-lake");
        metrics = IngestionMetrics.create();
        String base = server.url("/3").toString().replaceAll("/+$", "").replace("localhost", "127.0.0.1");
        config = MockServers.configFor(server, Map.of("tmdbwh.tmdb.export-base-url", base));
        client = new TmdbClient(config.getTmdb(), metrics);
        mock = new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer());
        producer = new KafkaEventProducer(mock, metrics);
    }

    @AfterEach
    void tearDown() throws IOException {
        client.close();
        metrics.close();
        server.close();
    }

    private JobResult run(List<EntityType> types, List<String> windows, boolean includePopular) {
        PopularityPollerJob job = new PopularityPollerJob(config, client, store, producer, metrics, CLOCK);
        return job.run(new PopularityPollerJob.Options()
                .entityTypes(types)
                .windows(windows)
                .includePopular(includePopular)
                .writeLake(true)
                .emitKafka(true));
    }

    @Test
    void publishesPopularityEventsWithRankAndEventTime() {
        server.enqueue(new MockResponse().setBody(MockServers.page(1, 1,
                "[{\"id\":872585,\"title\":\"Oppenheimer\",\"popularity\":148.3,\"vote_average\":8.1,\"vote_count\":7600},"
                        + "{\"id\":155,\"title\":\"The Dark Knight\",\"popularity\":105.6,\"vote_average\":8.5,\"vote_count\":33000}]")));

        JobResult result = run(List.of(EntityType.MOVIE), List.of("day"), false);

        assertThat(result.getSuccess()).isEqualTo(2);
        assertThat(result.getKafkaSent()).isEqualTo(2);

        List<ProducerRecord<String, byte[]>> records = mock.history();
        assertThat(records).hasSize(2);
        assertThat(records).extracting(r -> r.key()).containsExactly("movie:872585", "movie:155");

        EventEnvelope first = EventEnvelope.fromBytes(records.get(0).value());
        assertThat(first.getEventType()).isEqualTo(EventType.POPULARITY_OBSERVED);
        // 事件时间 = 观测时间，实时窗口聚合以此为准
        assertThat(first.getEventTime()).isEqualTo(CLOCK.millis());
        PopularityEvent event = first.payloadAs(PopularityEvent.class);
        assertThat(event.getRank()).isEqualTo(1);
        assertThat(event.getListName()).isEqualTo("trending_movie_day");
        assertThat(event.getPopularity()).isEqualTo(148.3);
        assertThat(event.getVoteCount()).isEqualTo(7600);
        assertThat(event.getTitle()).isEqualTo("Oppenheimer");

        PopularityEvent second = EventEnvelope.fromBytes(records.get(1).value()).payloadAs(PopularityEvent.class);
        assertThat(second.getRank()).isEqualTo(2);
    }

    @Test
    void writesLakeSnapshot() throws IOException {
        server.enqueue(new MockResponse().setBody(MockServers.page(1, 1,
                "[{\"id\":872585,\"title\":\"Oppenheimer\",\"popularity\":148.3}]")));

        JobResult result = run(List.of(EntityType.MOVIE), List.of("day"), false);

        assertThat(result.getWrittenKeys()).hasSize(1);
        assertThat(result.getWrittenKeys().get(0)).contains("/dt=2026-09-30/");
    }

    @Test
    void multipleListsArePolled() {
        // trending movie day、trending movie week、popular movie
        for (int i = 0; i < 3; i++) {
            server.enqueue(new MockResponse().setBody(MockServers.page(1, 1, "[{\"id\":1,\"title\":\"M\",\"popularity\":1.0}]")));
        }

        JobResult result = run(List.of(EntityType.MOVIE), List.of("day", "week"), true);

        assertThat(result.getSuccess()).isEqualTo(3);
        List<String> listNames = new ArrayList<>();
        for (ProducerRecord<String, byte[]> record : mock.history()) {
            listNames.add(EventEnvelope.fromBytes(record.value()).payloadAs(PopularityEvent.class).getListName());
        }
        assertThat(listNames).containsExactly("trending_movie_day", "trending_movie_week", "popular_movie");
    }

    @Test
    void entriesWithoutIdAreSkipped() {
        server.enqueue(new MockResponse().setBody(MockServers.page(1, 1, "[{\"title\":\"no id\"}]")));

        JobResult result = run(List.of(EntityType.MOVIE), List.of("day"), false);

        assertThat(result.getNotFound()).isEqualTo(1);
        assertThat(result.getSuccess()).isZero();
        assertThat(mock.history()).isEmpty();
    }

    @Test
    void toEventMapsTvNameField() {
        JsonNode node = JsonUtils.readTree(
                "{\"id\":1399,\"name\":\"Game of Thrones\",\"popularity\":346.098,\"vote_average\":8.456}");
        PopularityEvent event = PopularityPollerJob.toEvent(EntityType.TV, node, 3, "trending_tv_day", 123L);
        assertThat(event).isNotNull();
        assertThat(event.getTitle()).isEqualTo("Game of Thrones");
        assertThat(event.getRank()).isEqualTo(3);
        assertThat(PopularityPollerJob.toEvent(EntityType.TV, JsonUtils.readTree("{}"), 1, "l", 0L)).isNull();
    }

    @Test
    void eventKeyUsesEntityTypePrefix() {
        server.enqueue(new MockResponse().setBody(MockServers.page(1, 1, "[{\"id\":1399,\"name\":\"GOT\",\"popularity\":1.0}]")));

        run(List.of(EntityType.TV), List.of("day"), false);

        assertThat(mock.history()).extracting(r -> r.key()).containsExactly("tv:1399");
        assertThat(mock.history().get(0).topic()).isEqualTo("tmdb.popularity");
        assertThat(EventEnvelope.fromBytes(mock.history().get(0).value()).getEventId()).hasSize(32);
    }
}
