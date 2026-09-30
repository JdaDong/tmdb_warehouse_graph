package com.tmdbwh.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.tmdbwh.common.json.JsonUtils;
import com.tmdbwh.common.model.EntityType;
import com.tmdbwh.common.model.EventEnvelope;
import com.tmdbwh.common.model.EventType;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 事件编解码：坏消息必须可识别而不是抛异常。 */
class EventCodecTest {

    private static final Clock CLOCK = Clock.fixed(
            java.time.Instant.parse("2026-09-30T10:00:00Z"), ZoneOffset.UTC);

    private static EventEnvelope envelope(EntityType entityType, long id, String payload) {
        return EventEnvelope.create(EventType.POPULARITY_OBSERVED, entityType, id,
                CLOCK.millis(), JsonUtils.readTree(payload), "test", null, CLOCK);
    }

    @Test
    void roundTripPreservesKeyFields() {
        EventEnvelope original = envelope(EntityType.MOVIE, 27205L,
                "{\"popularity\":83.95,\"vote_average\":8.4,\"vote_count\":35000,\"title\":\"Inception\","
                        + "\"list_name\":\"trending_movie_day\"}");

        Optional<EventEnvelope> decoded = EventCodec.decode(EventCodec.encode(original));

        assertThat(decoded).isPresent();
        EventEnvelope result = decoded.get();
        assertThat(result.getEntityType()).isEqualTo(EntityType.MOVIE);
        assertThat(result.getEventType()).isEqualTo(EventType.POPULARITY_OBSERVED);
        assertThat(result.getEntityId()).isEqualTo(27205L);
        assertThat(result.getEventTime()).isEqualTo(CLOCK.millis());
        assertThat(result.getContentHash()).isEqualTo(original.getContentHash());
        assertThat(result.getEventId()).isEqualTo(original.getEventId());
    }

    @Test
    void extractsMetricFields() {
        EventEnvelope event = envelope(EntityType.MOVIE, 1L,
                "{\"popularity\":12.5,\"vote_average\":7.5,\"vote_count\":120,\"title\":\"A\","
                        + "\"list_name\":\"popular\"}");

        assertThat(EventCodec.popularityOf(event)).contains(12.5);
        assertThat(EventCodec.voteAverageOf(event)).contains(7.5);
        assertThat(EventCodec.voteCountOf(event)).contains(120L);
        assertThat(EventCodec.titleOf(event)).isEqualTo("A");
        assertThat(EventCodec.listNameOf(event)).isEqualTo("popular");
    }

    @Test
    void missingFieldsYieldEmptyRatherThanException() {
        EventEnvelope event = envelope(EntityType.MOVIE, 1L, "{\"title\":\"A\"}");

        assertThat(EventCodec.popularityOf(event)).isEmpty();
        assertThat(EventCodec.voteAverageOf(event)).isEmpty();
        assertThat(EventCodec.voteCountOf(event)).isEmpty();
        assertThat(EventCodec.listNameOf(event)).isEmpty();
    }

    @Test
    void malformedInputIsRejectedWithoutThrowing() {
        assertThat(EventCodec.decode((byte[]) null)).isEmpty();
        assertThat(EventCodec.decode(new byte[0])).isEmpty();
        assertThat(EventCodec.decode("not-json")).isEmpty();
        assertThat(EventCodec.decode("{}")).isEmpty();
        assertThat(EventCodec.decode("[]")).isEmpty();
        // 缺少关键字段
        assertThat(EventCodec.decode("{\"eventType\":\"POPULARITY_OBSERVED\"}")).isEmpty();
    }

    @Test
    void unknownEnumValuesAreIgnored() {
        // 上游新增枚举值时，旧作业应忽略该消息（进 DLQ）而不是崩溃重启
        String json = "{\"eventType\":\"FUTURE_TYPE\",\"entityType\":\"movie\",\"entityId\":1,\"eventTime\":1}";
        assertThat(EventCodec.decode(json)).isEmpty();
        assertThat(EventCodec.parseEventType("FUTURE_TYPE")).isNull();
        assertThat(EventCodec.parseEventType(null)).isNull();
    }

    @Test
    void unknownEntityTypeIsRejected() {
        String json = "{\"eventType\":\"POPULARITY_OBSERVED\",\"entityType\":\"spaceship\",\"entityId\":1,"
                + "\"eventTime\":1}";
        assertThat(EventCodec.decode(json)).isEmpty();
    }

    @Test
    void payloadIsAvailableAfterDecoding() {
        EventEnvelope original = envelope(EntityType.TV, 1399L, "{\"popularity\":9.9}");
        JsonNode payload = EventCodec.decode(EventCodec.encode(original)).orElseThrow().getPayload();
        assertThat(payload.get("popularity").asDouble()).isEqualTo(9.9);
    }
}
