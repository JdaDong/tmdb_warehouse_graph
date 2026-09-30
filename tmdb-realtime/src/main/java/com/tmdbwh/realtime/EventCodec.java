package com.tmdbwh.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.tmdbwh.common.json.JsonUtils;
import com.tmdbwh.common.model.EntityType;
import com.tmdbwh.common.model.EventEnvelope;
import com.tmdbwh.common.model.EventType;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Kafka 消息与 {@link EventEnvelope} 之间的编解码。
 *
 * <p>为什么不用 Flink 自带的 JSON 反序列化：信封里有 {@code EventType} / {@code EntityType} 枚举、
 * 以及需要跨算子安全传输的 {@code JsonNode} payload，通用反序列化在这两点上都会出问题
 * （枚举容错差、JsonNode 反序列化后类型不确定）。这里显式解析，并在失败时给出可诊断的错误。
 *
 * <p>解析失败<b>不抛异常</b>：单条坏消息不应让整个作业重启，而是返回空值由算子路由到死信队列。
 */
public final class EventCodec {

    private EventCodec() {}

    /** 编码为 UTF-8 JSON 字节。 */
    public static byte[] encode(EventEnvelope envelope) {
        return JsonUtils.toJsonBytes(envelope);
    }

    /**
     * 解码；失败或结构不完整时返回 {@link Optional#empty()}。
     *
     * <p>判定为"不完整"的条件（缺任一即视为坏消息）：eventType、entityType、entityId、eventTime。
     */
    public static Optional<EventEnvelope> decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return Optional.empty();
        }
        String json = new String(bytes, StandardCharsets.UTF_8);
        return decode(json);
    }

    /** 解码字符串形式。 */
    public static Optional<EventEnvelope> decode(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        JsonNode node;
        try {
            node = JsonUtils.readTree(json);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (node == null || !node.isObject()) {
            return Optional.empty();
        }
        try {
            EventEnvelope envelope = new EventEnvelope();
            envelope.setSchemaVersion(node.path("schemaVersion").asInt(EventEnvelope.CURRENT_SCHEMA_VERSION));
            String eventTypeValue = node.path("eventType").asText(null);
            String entityTypeValue = node.path("entityType").asText(null);
            if (eventTypeValue == null || entityTypeValue == null) {
                return Optional.empty();
            }
            EventType eventType = parseEventType(eventTypeValue);
            if (eventType == null) {
                return Optional.empty();
            }
            envelope.setEventType(eventType);
            envelope.setEntityType(EntityType.fromValue(entityTypeValue));
            JsonNode entityId = node.get("entityId");
            JsonNode eventTime = node.get("eventTime");
            if (entityId == null || eventTime == null || !entityId.isNumber() || !eventTime.isNumber()) {
                return Optional.empty();
            }
            envelope.setEntityId(entityId.asLong());
            envelope.setEventTime(eventTime.asLong());
            envelope.setIngestTime(node.path("ingestTime").asLong(envelope.getEventTime()));
            envelope.setEventId(node.path("eventId").asText(null));
            envelope.setSource(node.path("source").asText("unknown"));
            envelope.setTraceId(node.path("traceId").asText(null));
            envelope.setContentHash(node.path("contentHash").asText(null));
            JsonNode payload = node.get("payload");
            envelope.setPayload(payload == null || payload.isNull() ? null : payload);
            return Optional.of(envelope);
        } catch (IllegalArgumentException e) {
            // 未知的事件类型 / 实体类型：上游新增枚举值时不应打挂作业
            return Optional.empty();
        }
    }

    /**
     * 解析事件类型；未知类型返回 null。
     *
     * <p>上游新增事件类型时，旧版本作业应忽略而不是崩溃（否则一次发布就会让实时链路整体重启循环）。
     */
    static EventType parseEventType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return EventType.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 从事件中提取热度值。
     *
     * <p>热度可能嵌套在 payload 的不同位置（详情报文为 {@code popularity}，榜单报文为 {@code popularity} +
     * {@code vote_average}），这里统一在 payload 顶层取，缺失时返回空。
     */
    public static Optional<Double> popularityOf(EventEnvelope envelope) {
        if (envelope == null || envelope.getPayload() == null) {
            return Optional.empty();
        }
        JsonNode value = envelope.getPayload().get("popularity");
        return value != null && value.isNumber() ? Optional.of(value.asDouble()) : Optional.empty();
    }

    /** 提取评分，缺失返回空。 */
    public static Optional<Double> voteAverageOf(EventEnvelope envelope) {
        if (envelope == null || envelope.getPayload() == null) {
            return Optional.empty();
        }
        JsonNode value = envelope.getPayload().get("vote_average");
        return value != null && value.isNumber() ? Optional.of(value.asDouble()) : Optional.empty();
    }

    /** 提取评分人数，缺失返回空。 */
    public static Optional<Long> voteCountOf(EventEnvelope envelope) {
        if (envelope == null || envelope.getPayload() == null) {
            return Optional.empty();
        }
        JsonNode value = envelope.getPayload().get("vote_count");
        return value != null && value.isNumber() ? Optional.of(value.asLong()) : Optional.empty();
    }

    /** 提取标题，缺失返回空串。 */
    public static String titleOf(EventEnvelope envelope) {
        if (envelope == null || envelope.getPayload() == null) {
            return "";
        }
        JsonNode value = envelope.getPayload().get("title");
        return value == null || value.isNull() ? "" : value.asText("");
    }

    /** 提取榜单名（list_name），缺失返回空串。 */
    public static String listNameOf(EventEnvelope envelope) {
        if (envelope == null || envelope.getPayload() == null) {
            return "";
        }
        JsonNode value = envelope.getPayload().get("list_name");
        return value == null || value.isNull() ? "" : value.asText("");
    }
}
