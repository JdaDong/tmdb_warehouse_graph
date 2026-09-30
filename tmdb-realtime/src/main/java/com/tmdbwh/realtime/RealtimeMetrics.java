package com.tmdbwh.realtime;

import com.tmdbwh.common.model.EventEnvelope;
import java.util.Objects;

/**
 * 实时链路度量。
 *
 * <p>关键指标与它们的用途：
 *
 * <ul>
 *   <li>{@code realtime_end_to_end_latency_ms}：事件时间 → 进入平台的时间差，
 *       是判断"实时是否还实时"的唯一可靠指标（吞吐高不代表延迟低）；
 *   <li>{@code realtime_duplicate_events}：被去重丢弃的事件数，突增通常说明上游在重复投递或作业发生了重放；
 *   <li>{@code realtime_invalid_events}：坏消息数，突增说明上游结构变更或序列化有问题；
 *   <li>{@code clickhouse_written_rows} / {@code clickhouse_flush_failures}：Sink 写入量与失败次数。
 * </ul>
 */
public final class RealtimeMetrics {

    /** 端到端延迟（毫秒）。 */
    public static final String END_TO_END_LATENCY = "realtime_end_to_end_latency_ms";

    /** 重复事件计数。 */
    public static final String DUPLICATE_EVENTS = "realtime_duplicate_events";

    /** 坏消息计数。 */
    public static final String INVALID_EVENTS = "realtime_invalid_events";

    /** 写入行数。 */
    public static final String WRITTEN_ROWS = "clickhouse_written_rows";

    /** 写入失败次数。 */
    public static final String FLUSH_FAILURES = "clickhouse_flush_failures";

    /** 延迟告警阈值（毫秒）：超过该值说明实时链路已经明显落后。 */
    public static final long LATENCY_ALERT_THRESHOLD_MS = 300_000L;

    private RealtimeMetrics() {}

    /**
     * 端到端延迟：事件时间到入库时间的差值。
     *
     * @return 毫秒，负值（时钟回拨或事件时间晚于处理时间）按 0 处理，避免污染聚合
     */
    public static long endToEndLatencyMs(EventEnvelope envelope, long nowMillis) {
        Objects.requireNonNull(envelope, "envelope");
        long latency = nowMillis - envelope.getEventTime();
        return latency < 0 ? 0L : latency;
    }

    /** 是否超过延迟告警阈值。 */
    public static boolean isLatencyTooHigh(long latencyMillis) {
        return latencyMillis > LATENCY_ALERT_THRESHOLD_MS;
    }
}
