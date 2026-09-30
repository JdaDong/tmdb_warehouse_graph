package com.tmdbwh.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.io.Serializable;

/**
 * 热度观测事件：某时刻某实体在某榜单上的热度 / 评分 / 排名快照。
 *
 * <p>实时链路以 {@code observedAt} 为事件时间做窗口聚合与飙升检测。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class PopularityEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    private EntityType entityType;
    private long entityId;
    private String title;
    private double popularity;
    private Double voteAverage;
    private Integer voteCount;
    /** 在榜单中的名次（从 1 开始）。 */
    private int rank;
    /** 榜单来源：trending_day / trending_week / popular / now_playing 等。 */
    private String listName;
    /** 观测时间（epoch ms）。 */
    private long observedAt;

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

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public double getPopularity() {
        return popularity;
    }

    public void setPopularity(double popularity) {
        this.popularity = popularity;
    }

    public Double getVoteAverage() {
        return voteAverage;
    }

    public void setVoteAverage(Double voteAverage) {
        this.voteAverage = voteAverage;
    }

    public Integer getVoteCount() {
        return voteCount;
    }

    public void setVoteCount(Integer voteCount) {
        this.voteCount = voteCount;
    }

    public int getRank() {
        return rank;
    }

    public void setRank(int rank) {
        this.rank = rank;
    }

    public String getListName() {
        return listName;
    }

    public void setListName(String listName) {
        this.listName = listName;
    }

    public long getObservedAt() {
        return observedAt;
    }

    public void setObservedAt(long observedAt) {
        this.observedAt = observedAt;
    }

    @Override
    public String toString() {
        return "PopularityEvent{" + entityType + ":" + entityId + ", popularity=" + popularity + ", rank=" + rank
                + ", list=" + listName + "}";
    }
}
