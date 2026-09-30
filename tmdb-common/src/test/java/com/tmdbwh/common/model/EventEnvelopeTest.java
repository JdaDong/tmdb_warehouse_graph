package com.tmdbwh.common.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.tmdbwh.common.json.JsonUtils;
import com.tmdbwh.common.testing.Fixtures;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class EventEnvelopeTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-30T08:00:00Z"), ZoneOffset.UTC);
    private static final long EVENT_TIME = Instant.parse("2026-09-30T07:59:00Z").toEpochMilli();

    private static EventEnvelope changeOf(long id, String traceId) {
        ChangeEvent payload = new ChangeEvent(EntityType.MOVIE, id, false, "2026-09-29", "2026-09-30", EVENT_TIME);
        return EventEnvelope.create(EventType.ENTITY_CHANGED, EntityType.MOVIE, id, EVENT_TIME, payload,
                "ingestion.changes", traceId, CLOCK);
    }

    @Test
    void eventIdIsDeterministicForIdempotency() {
        EventEnvelope a = changeOf(27205L, "trace-a");
        EventEnvelope b = changeOf(27205L, "trace-b");

        assertThat(a.getEventId()).hasSize(32).isEqualTo(b.getEventId());
        assertThat(a.getContentHash()).hasSize(64).isEqualTo(b.getContentHash());
        assertThat(changeOf(155L, "trace-a").getEventId()).isNotEqualTo(a.getEventId());
    }

    @Test
    void contentHashIgnoresJsonFieldOrder() {
        JsonNode p1 = JsonUtils.readTree("{\"id\":1,\"title\":\"x\",\"genres\":[{\"id\":1,\"name\":\"a\"}]}");
        JsonNode p2 = JsonUtils.readTree("{\"genres\":[{\"name\":\"a\",\"id\":1}],\"title\":\"x\",\"id\":1}");

        EventEnvelope e1 = EventEnvelope.create(EventType.ENTITY_SNAPSHOT, EntityType.MOVIE, 1L, EVENT_TIME, p1,
                "s", null, CLOCK);
        EventEnvelope e2 = EventEnvelope.create(EventType.ENTITY_SNAPSHOT, EntityType.MOVIE, 1L, EVENT_TIME, p2,
                "s", null, CLOCK);

        assertThat(e1.getContentHash()).isEqualTo(e2.getContentHash());
        assertThat(e1.getEventId()).isEqualTo(e2.getEventId());
        assertThat(e1.getTraceId()).isNotNull().hasSize(32);
    }

    @Test
    void envelopeMetadata() {
        EventEnvelope e = changeOf(27205L, "t");

        assertThat(e.kafkaKey()).isEqualTo("movie:27205");
        assertThat(e.getSchemaVersion()).isEqualTo(EventEnvelope.CURRENT_SCHEMA_VERSION);
        assertThat(e.getIngestTime()).isEqualTo(CLOCK.millis());
        assertThat(e.getEventTime()).isEqualTo(EVENT_TIME);
        assertThat(e.getSource()).isEqualTo("ingestion.changes");
        assertThat(e.toString()).contains("ENTITY_CHANGED").contains("27205");
    }

    @Test
    void jsonBytesRoundTripPreservesPayload() {
        EventEnvelope original = changeOf(27205L, "t");
        byte[] bytes = original.toBytes();

        String json = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(json).contains("\"event_id\"").contains("\"entity_type\":\"movie\"").contains("\"payload\"");
        assertThat(json).doesNotContain("kafka_key");

        EventEnvelope parsed = EventEnvelope.fromBytes(bytes);
        assertThat(parsed.getEventId()).isEqualTo(original.getEventId());
        assertThat(parsed.getEventType()).isEqualTo(EventType.ENTITY_CHANGED);
        assertThat(parsed.getEntityType()).isEqualTo(EntityType.MOVIE);
        ChangeEvent payload = parsed.payloadAs(ChangeEvent.class);
        assertThat(payload.getEntityId()).isEqualTo(27205L);
        assertThat(payload.getWindowEnd()).isEqualTo("2026-09-30");
    }

    @Test
    void payloadAsReturnsNullWhenMissing() {
        assertThat(new EventEnvelope().payloadAs(Movie.class)).isNull();
    }

    @Test
    void javaSerializationSupportsLargePayloads() throws Exception {
        // 构造 > 64KB 的 payload，验证不受 writeUTF 长度限制
        Movie movie = JsonUtils.fromJson(Fixtures.read("movie_27205.json"), Movie.class);
        StringBuilder overview = new StringBuilder();
        for (int i = 0; i < 9000; i++) {
            overview.append("长文本overview");
        }
        movie.setOverview(overview.toString());
        EventEnvelope original = EventEnvelope.create(EventType.ENTITY_SNAPSHOT, EntityType.MOVIE, movie.getId(),
                EVENT_TIME, movie, "test", "t", CLOCK);

        EventEnvelope copy = javaRoundTrip(original);
        assertThat(copy.getEventId()).isEqualTo(original.getEventId());
        assertThat(copy.payloadAs(Movie.class).getOverview()).hasSize(overview.length());

        EventEnvelope empty = javaRoundTrip(new EventEnvelope());
        assertThat(empty.getPayload()).isNull();
    }

    private static EventEnvelope javaRoundTrip(EventEnvelope e) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bos)) {
            out.writeObject(e);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bos.toByteArray()))) {
            return (EventEnvelope) in.readObject();
        }
    }

    @Test
    void popularityEventSerializesSnakeCase() {
        PopularityEvent p = new PopularityEvent();
        p.setEntityType(EntityType.MOVIE);
        p.setEntityId(27205L);
        p.setPopularity(99.5);
        p.setRank(1);
        p.setListName("trending_day");
        p.setObservedAt(EVENT_TIME);

        String json = JsonUtils.toJson(p);
        assertThat(json).contains("\"list_name\":\"trending_day\"").contains("\"observed_at\":" + EVENT_TIME);
        PopularityEvent back = JsonUtils.fromJson(json, PopularityEvent.class);
        assertThat(back.getPopularity()).isEqualTo(99.5);
        assertThat(back.getRank()).isEqualTo(1);
        assertThat(back.toString()).contains("trending_day");
    }
}
