package com.tmdbwh.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.tmdbwh.common.json.JsonUtils;
import com.tmdbwh.common.model.EntityType;
import com.tmdbwh.common.model.EventEnvelope;
import com.tmdbwh.common.model.EventType;
import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** 端到端延迟度量。 */
class RealtimeMetricsTest {

    private static EventEnvelope event(long eventTime) {
        JsonNode payload = JsonUtils.readTree("{\"popularity\":1.0}");
        return EventEnvelope.create(EventType.POPULARITY_OBSERVED, EntityType.MOVIE, 1L, eventTime, payload,
                "test", null, Clock.systemUTC());
    }

    @Test
    void latencyIsDifferenceBetweenNowAndEventTime() {
        assertThat(RealtimeMetrics.endToEndLatencyMs(event(1_000L), 6_000L)).isEqualTo(5_000L);
    }

    @Test
    void negativeLatencyIsClampedToZero() {
        // 时钟回拨 / 事件时间晚于处理时间：负值会让 avg 失真，统一按 0 处理
        assertThat(RealtimeMetrics.endToEndLatencyMs(event(10_000L), 5_000L)).isZero();
    }

    @Test
    void latencyThresholdDetectsStalePipeline() {
        assertThat(RealtimeMetrics.isLatencyTooHigh(RealtimeMetrics.LATENCY_ALERT_THRESHOLD_MS + 1)).isTrue();
        assertThat(RealtimeMetrics.isLatencyTooHigh(RealtimeMetrics.LATENCY_ALERT_THRESHOLD_MS)).isFalse();
    }

    @Test
    void metricNamesAreStable() {
        // 指标名变化会让既有告警规则与 Grafana 面板失效
        assertThat(RealtimeMetrics.END_TO_END_LATENCY).isEqualTo("realtime_end_to_end_latency_ms");
        assertThat(RealtimeMetrics.DUPLICATE_EVENTS).isEqualTo("realtime_duplicate_events");
        assertThat(RealtimeMetrics.INVALID_EVENTS).isEqualTo("realtime_invalid_events");
        assertThat(RealtimeMetrics.WRITTEN_ROWS).isEqualTo("clickhouse_written_rows");
        assertThat(RealtimeMetrics.FLUSH_FAILURES).isEqualTo("clickhouse_flush_failures");
    }
}
