package com.tmdbwh.realtime;

import java.io.Serializable;
import java.util.Objects;

/**
 * 一个滚动窗口内某部电影的热度聚合结果。
 *
 * <p>字段与 {@code rt.rt_movie_popularity} 对齐（迁移脚本 V9），
 * 便于窗口输出直接映射为一行写入语句，不需要在 Sink 里再做字段拼装。
 */
public final class WindowedPopularity implements Serializable {

    private static final long serialVersionUID = 1L;

    private long entityId;
    private long windowStart;
    private long windowEnd;
    private long sampleCount;
    private double avgPopularity;
    private double maxPopularity;
    private double avgVoteAverage;
    private long voteCount;
    private String title;
    private String listName;

    public WindowedPopularity() {}

    @SuppressWarnings("java:S107")
    public WindowedPopularity(long entityId, long windowStart, long windowEnd, long sampleCount,
            double avgPopularity, double maxPopularity, double avgVoteAverage, long voteCount, String title,
            String listName) {
        this.entityId = entityId;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.sampleCount = sampleCount;
        this.avgPopularity = avgPopularity;
        this.maxPopularity = maxPopularity;
        this.avgVoteAverage = avgVoteAverage;
        this.voteCount = voteCount;
        this.title = title == null ? "" : title;
        this.listName = listName == null ? "" : listName;
    }

    public long getEntityId() {
        return entityId;
    }

    public void setEntityId(long entityId) {
        this.entityId = entityId;
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

    public long getSampleCount() {
        return sampleCount;
    }

    public void setSampleCount(long sampleCount) {
        this.sampleCount = sampleCount;
    }

    public double getAvgPopularity() {
        return avgPopularity;
    }

    public void setAvgPopularity(double avgPopularity) {
        this.avgPopularity = avgPopularity;
    }

    public double getMaxPopularity() {
        return maxPopularity;
    }

    public void setMaxPopularity(double maxPopularity) {
        this.maxPopularity = maxPopularity;
    }

    public double getAvgVoteAverage() {
        return avgVoteAverage;
    }

    public void setAvgVoteAverage(double avgVoteAverage) {
        this.avgVoteAverage = avgVoteAverage;
    }

    public long getVoteCount() {
        return voteCount;
    }

    public void setVoteCount(long voteCount) {
        this.voteCount = voteCount;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title == null ? "" : title;
    }

    public String getListName() {
        return listName;
    }

    public void setListName(String listName) {
        this.listName = listName == null ? "" : listName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof WindowedPopularity)) {
            return false;
        }
        WindowedPopularity that = (WindowedPopularity) o;
        return entityId == that.entityId && windowStart == that.windowStart && windowEnd == that.windowEnd
                && sampleCount == that.sampleCount && voteCount == that.voteCount
                && Double.compare(avgPopularity, that.avgPopularity) == 0
                && Double.compare(maxPopularity, that.maxPopularity) == 0
                && Double.compare(avgVoteAverage, that.avgVoteAverage) == 0
                && Objects.equals(title, that.title) && Objects.equals(listName, that.listName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(entityId, windowStart, windowEnd, sampleCount, avgPopularity, maxPopularity,
                avgVoteAverage, voteCount, title, listName);
    }

    @Override
    public String toString() {
        return "WindowedPopularity{id=" + entityId + ", window=[" + windowStart + "," + windowEnd + "]"
                + ", n=" + sampleCount + ", avg=" + avgPopularity + "}";
    }
}
