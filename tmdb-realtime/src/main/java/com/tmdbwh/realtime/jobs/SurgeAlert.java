package com.tmdbwh.realtime.jobs;

import java.io.Serializable;
import java.util.Objects;

/** 热度飙升告警（对应 {@code rt.rt_surge_alert}）。 */
public final class SurgeAlert implements Serializable {

    private static final long serialVersionUID = 1L;

    private String alertId;
    private String entityType;
    private long entityId;
    private String title;
    private long windowStart;
    private long windowEnd;
    private double baselinePopularity;
    private double currentPopularity;
    private double growthRatio;
    private long alertTime;

    public SurgeAlert() {}

    public SurgeAlert(String alertId, String entityType, long entityId, String title, long windowStart,
            long windowEnd, double baselinePopularity, double currentPopularity, double growthRatio,
            long alertTime) {
        this.alertId = alertId;
        this.entityType = entityType;
        this.entityId = entityId;
        this.title = title == null ? "" : title;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.baselinePopularity = baselinePopularity;
        this.currentPopularity = currentPopularity;
        this.growthRatio = growthRatio;
        this.alertTime = alertTime;
    }

    public String getAlertId() {
        return alertId;
    }

    public void setAlertId(String alertId) {
        this.alertId = alertId;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public long getEntityId() {
        return entityId;
    }

    public void setEntityId(long entityId) {
        this.entityId = entityId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title == null ? "" : title;
    }

    public long getWindowStart() {
        return windowStart;
    }

    public void setWindowStart(long windowStart) {
        this.windowStart = windowStart;
    }

    public long getWindowEnd() {
        return windowEnd;
    }

    public void setWindowEnd(long windowEnd) {
        this.windowEnd = windowEnd;
    }

    public double getBaselinePopularity() {
        return baselinePopularity;
    }

    public void setBaselinePopularity(double baselinePopularity) {
        this.baselinePopularity = baselinePopularity;
    }

    public double getCurrentPopularity() {
        return currentPopularity;
    }

    public void setCurrentPopularity(double currentPopularity) {
        this.currentPopularity = currentPopularity;
    }

    public double getGrowthRatio() {
        return growthRatio;
    }

    public void setGrowthRatio(double growthRatio) {
        this.growthRatio = growthRatio;
    }

    public long getAlertTime() {
        return alertTime;
    }

    public void setAlertTime(long alertTime) {
        this.alertTime = alertTime;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SurgeAlert)) {
            return false;
        }
        SurgeAlert that = (SurgeAlert) o;
        return entityId == that.entityId && windowStart == that.windowStart && windowEnd == that.windowEnd
                && alertTime == that.alertTime
                && Double.compare(baselinePopularity, that.baselinePopularity) == 0
                && Double.compare(currentPopularity, that.currentPopularity) == 0
                && Double.compare(growthRatio, that.growthRatio) == 0
                && Objects.equals(alertId, that.alertId) && Objects.equals(entityType, that.entityType)
                && Objects.equals(title, that.title);
    }

    @Override
    public int hashCode() {
        return Objects.hash(alertId, entityType, entityId, title, windowStart, windowEnd, baselinePopularity,
                currentPopularity, growthRatio, alertTime);
    }

    @Override
    public String toString() {
        return "SurgeAlert{id=" + alertId + ", ratio=" + growthRatio + ", baseline=" + baselinePopularity
                + ", current=" + currentPopularity + "}";
    }
}
