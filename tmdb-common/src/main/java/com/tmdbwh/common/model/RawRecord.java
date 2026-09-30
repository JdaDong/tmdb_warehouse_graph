package com.tmdbwh.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.io.Serializable;

/**
 * 数据湖原始区记录（NDJSON 中的一行）。
 *
 * <p>设计原则：<b>原样保留 TMDB 原始报文</b>，只在最外层补充采集元数据（schema_version / dt / ingest_time /
 * source）。这样上游 API 变更不会破坏已落地的历史数据，所有清洗与规范化都放在 DWD 层， 便于口径变更时重算。
 *
 * <pre>
 * {"schema_version":1,"entity_type":"movie","entity_id":27205,"dt":"2026-09-30",
 *  "ingest_time":1760000000000,"source":"tmdb.details","payload":{ ...TMDB 原始响应... }}
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class RawRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 记录结构版本，便于后续演进时兼容解析。 */
    public static final int SCHEMA_VERSION = 1;

    private int schemaVersion = SCHEMA_VERSION;
    private EntityType entityType;
    private long entityId;
    /** 业务分区日期 yyyy-MM-dd。 */
    private String dt;
    /** 采集时间（epoch ms）。 */
    private long ingestTime;
    /** 数据来源标识，如 tmdb.details / tmdb.changes。 */
    private String source;
    /** TMDB 原始报文（原样保留）。 */
    private JsonNode payload;

    public RawRecord() {}

    /**
     * 构建原始记录。
     *
     * @param entityType 实体类型
     * @param entityId 实体 ID
     * @param dt 业务日期（yyyy-MM-dd）
     * @param payload TMDB 原始报文
     * @param ingestTime 采集时间（epoch ms）
     */
    public static RawRecord of(EntityType entityType, long entityId, String dt, JsonNode payload, long ingestTime) {
        RawRecord r = new RawRecord();
        r.entityType = entityType;
        r.entityId = entityId;
        r.dt = dt;
        r.payload = payload;
        r.ingestTime = ingestTime;
        r.source = "tmdb.details";
        return r;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(int schemaVersion) {
        this.schemaVersion = schemaVersion;
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

    @JsonProperty("dt")
    public String getDt() {
        return dt;
    }

    public void setDt(String dt) {
        this.dt = dt;
    }

    public long getIngestTime() {
        return ingestTime;
    }

    public void setIngestTime(long ingestTime) {
        this.ingestTime = ingestTime;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public JsonNode getPayload() {
        return payload;
    }

    public void setPayload(JsonNode payload) {
        this.payload = payload;
    }

    @Override
    public String toString() {
        return "RawRecord{" + entityType + ":" + entityId + ", dt=" + dt + ", source=" + source + "}";
    }
}
