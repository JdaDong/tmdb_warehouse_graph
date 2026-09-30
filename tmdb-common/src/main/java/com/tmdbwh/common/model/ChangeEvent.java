package com.tmdbwh.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.io.Serializable;
import java.util.Objects;

/**
 * 实体变更事件（由 /{type}/changes 结果转换而来）。
 *
 * <p>TMDB changes 接口只返回"在某时间窗内发生过变更的 ID"，不含变更内容；采集侧据此回查详情， 下游据 {@code windowStart/windowEnd} 追溯来源批次。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ChangeEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    private EntityType entityType;
    private long entityId;
    private Boolean adult;
    /** 查询窗口起点（yyyy-MM-dd，UTC）。 */
    private String windowStart;
    /** 查询窗口终点（yyyy-MM-dd，UTC）。 */
    private String windowEnd;
    /** 采集侧发现该变更的时间（epoch ms）。 */
    private long detectedAt;

    public ChangeEvent() {}

    public ChangeEvent(EntityType entityType, long entityId, Boolean adult, String windowStart, String windowEnd,
            long detectedAt) {
        this.entityType = entityType;
        this.entityId = entityId;
        this.adult = adult;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.detectedAt = detectedAt;
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

    public Boolean getAdult() {
        return adult;
    }

    public void setAdult(Boolean adult) {
        this.adult = adult;
    }

    public String getWindowStart() {
        return windowStart;
    }

    public void setWindowStart(String windowStart) {
        this.windowStart = windowStart;
    }

    public String getWindowEnd() {
        return windowEnd;
    }

    public void setWindowEnd(String windowEnd) {
        this.windowEnd = windowEnd;
    }

    public long getDetectedAt() {
        return detectedAt;
    }

    public void setDetectedAt(long detectedAt) {
        this.detectedAt = detectedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ChangeEvent)) {
            return false;
        }
        ChangeEvent that = (ChangeEvent) o;
        return entityId == that.entityId
                && detectedAt == that.detectedAt
                && entityType == that.entityType
                && Objects.equals(adult, that.adult)
                && Objects.equals(windowStart, that.windowStart)
                && Objects.equals(windowEnd, that.windowEnd);
    }

    @Override
    public int hashCode() {
        return Objects.hash(entityType, entityId, adult, windowStart, windowEnd, detectedAt);
    }

    @Override
    public String toString() {
        return "ChangeEvent{" + entityType + ":" + entityId + ", window=" + windowStart + "~" + windowEnd + "}";
    }
}
