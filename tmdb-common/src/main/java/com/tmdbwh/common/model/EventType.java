package com.tmdbwh.common.model;

/** 平台事件类型，写入 {@link EventEnvelope#getEventType()}。 */
public enum EventType {
    /** 实体发生变更（来自 changes 接口），payload 为 {@link ChangeEvent}。 */
    ENTITY_CHANGED,
    /** 实体最新详情快照（变更后回查详情），payload 为 Movie / TvShow / Person 原始 JSON。 */
    ENTITY_SNAPSHOT,
    /** 热度观测（trending / popular 榜单轮询），payload 为 {@link PopularityEvent}。 */
    POPULARITY_OBSERVED
}
