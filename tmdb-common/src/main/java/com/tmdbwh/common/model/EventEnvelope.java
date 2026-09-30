package com.tmdbwh.common.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.tmdbwh.common.json.JsonUtils;
import com.tmdbwh.common.util.Hashing;
import java.io.Serializable;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * Kafka 事件信封：所有实时事件的统一外层结构。
 *
 * <p>设计要点：
 *
 * <ul>
 *   <li><b>确定性 eventId</b>：由 (eventType, entityType, entityId, eventTime, contentHash) 计算，重复投递得到相同 ID，
 *       下游据此幂等去重；
 *   <li><b>contentHash</b>：payload 规范化 JSON 的 SHA-256，用于判定"实体内容是否真的变化"（TMDB changes 常有无实质变化的记录）；
 *   <li><b>schemaVersion</b>：信封结构版本，消费端按版本兼容解析，演进时只增不改字段；
 *   <li><b>kafkaKey</b>：{@code entityType:entityId}，保证同一实体的事件落入同一分区、按序消费。
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class EventEnvelope implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 当前信封结构版本。 */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    private String eventId;
    private EventType eventType;
    private int schemaVersion = CURRENT_SCHEMA_VERSION;
    private String source;
    private EntityType entityType;
    private long entityId;
    /** 业务事件时间（epoch ms），实时窗口计算以此为准。 */
    private long eventTime;
    /** 进入平台的时间（epoch ms），用于度量端到端延迟。 */
    private long ingestTime;
    private String traceId;
    private String contentHash;
    private transient JsonNode payload;

    /** 反序列化用无参构造。 */
    public EventEnvelope() {}

    /**
     * 构建事件信封并计算 contentHash / eventId。
     *
     * @param eventType 事件类型
     * @param entityType 实体类型
     * @param entityId 实体 ID
     * @param eventTime 业务事件时间（epoch ms）
     * @param payload 事件内容（POJO 或 JsonNode）
     * @param source 事件来源，如 {@code ingestion.changes}
     * @param traceId 链路追踪 ID，为 null 时自动生成
     * @param clock 时钟，用于生成 ingestTime
     */
    public static EventEnvelope create(EventType eventType, EntityType entityType, long entityId, long eventTime,
            Object payload, String source, String traceId, Clock clock) {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(clock, "clock");
        EventEnvelope env = new EventEnvelope();
        env.eventType = eventType;
        env.entityType = entityType;
        env.entityId = entityId;
        env.eventTime = eventTime;
        env.source = source;
        env.traceId = traceId != null ? traceId : UUID.randomUUID().toString().replace("-", "");
        env.ingestTime = clock.millis();
        env.payload = payload instanceof JsonNode ? (JsonNode) payload : JsonUtils.valueToTree(payload);
        env.contentHash = Hashing.sha256Hex(JsonUtils.canonicalJson(env.payload));
        env.eventId = Hashing.deterministicId(eventType, entityType, entityId, eventTime, env.contentHash);
        return env;
    }

    /** Kafka 消息 key：同一实体的所有事件保持分区内有序。 */
    @JsonIgnore
    public String kafkaKey() {
        return entityType.getApiPath() + ":" + entityId;
    }

    /** 把 payload 转换为指定类型。 */
    public <T> T payloadAs(Class<T> type) {
        if (payload == null) {
            return null;
        }
        return JsonUtils.treeToValue(payload, type);
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public EventType getEventType() {
        return eventType;
    }

    public void setEventType(EventType eventType) {
        this.eventType = eventType;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(int schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public EntityType getEntityType() {
        return entityType;
    }

    public void setEntityType(EntityType entityType) {
        this.entityType = entityType;
    }

    public long getEntityId() {
        return entityId;
    }

    public void setEntityId(long entityId) {
        this.entityId = entityId;
    }

    public long getEventTime() {
        return eventTime;
    }

    public void setEventTime(long eventTime) {
        this.eventTime = eventTime;
    }

    public long getIngestTime() {
        return ingestTime;
    }

    public void setIngestTime(long ingestTime) {
        this.ingestTime = ingestTime;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public JsonNode getPayload() {
        return payload;
    }

    public void setPayload(JsonNode payload) {
        this.payload = payload;
    }

    /** 序列化为 JSON 字节（Kafka value）。 */
    public byte[] toBytes() {
        return JsonUtils.toJsonBytes(this);
    }

    /** 从 JSON 字节解析。 */
    public static EventEnvelope fromBytes(byte[] bytes) {
        return JsonUtils.fromJson(bytes, EventEnvelope.class);
    }

    // Java 序列化时 JsonNode 以"长度 + UTF-8 字节"形式传输（writeUTF 有 64KB 上限，详情 payload 可能超出）
    private void writeObject(java.io.ObjectOutputStream out) throws java.io.IOException {
        out.defaultWriteObject();
        if (payload == null) {
            out.writeInt(-1);
        } else {
            byte[] bytes = JsonUtils.toJsonBytes(payload);
            out.writeInt(bytes.length);
            out.write(bytes);
        }
    }

    private void readObject(java.io.ObjectInputStream in) throws java.io.IOException, ClassNotFoundException {
        in.defaultReadObject();
        int length = in.readInt();
        if (length < 0) {
            this.payload = null;
        } else {
            byte[] bytes = new byte[length];
            in.readFully(bytes);
            this.payload = JsonUtils.readTree(bytes);
        }
    }

    @Override
    public String toString() {
        return "EventEnvelope{" + eventType + " " + entityType + ":" + entityId + ", eventId=" + eventId
                + ", eventTime=" + eventTime + "}";
    }
}
