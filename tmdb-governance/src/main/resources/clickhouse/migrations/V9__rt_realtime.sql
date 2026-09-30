-- V9 实时层（RT）：实时链路明细
--
-- 特点：只保留很短周期（30 天），因为实时明细的价值在于"当前状态"，
-- 历史分析走 DWD / DWS；同时也避免实时库无限增长。
-- ReplacingMergeTree(version)：version 取事件时间，保证"同一实体的最新状态"最终一致。

CREATE TABLE IF NOT EXISTS rt.rt_movie_popularity
(
    entity_id           UInt64                  COMMENT '电影 ID',
    event_time          DateTime                COMMENT '观测时间（事件时间）',
    popularity          Float64                 COMMENT '热度',
    vote_average        Nullable(Float64)       COMMENT '评分',
    vote_count          Nullable(UInt32)        COMMENT '评分人数',
    title               String                  COMMENT '标题',
    list_name           LowCardinality(String)  COMMENT '来源榜单（trending_movie_day 等）',
    ingest_time         DateTime                COMMENT '进入平台时间',
    version             DateTime                COMMENT '去重版本号（取事件时间）'
)
ENGINE = ReplacingMergeTree(version)
PARTITION BY toYYYYMM(event_time)
ORDER BY (entity_id, event_time)
TTL event_time + INTERVAL 30 DAY
SETTINGS index_granularity = 8192;

CREATE TABLE IF NOT EXISTS rt.rt_popularity_event
(
    event_id            String                  COMMENT '事件 ID（内容哈希）',
    entity_type         LowCardinality(String)  COMMENT 'movie / tv / person',
    entity_id           UInt64                  COMMENT '实体 ID',
    event_time          DateTime                COMMENT '观测时间',
    popularity          Float64                 COMMENT '热度',
    rank                UInt32                  COMMENT '榜单名次',
    list_name           LowCardinality(String)  COMMENT '来源榜单',
    ingest_time         DateTime                COMMENT '进入平台时间'
)
ENGINE = ReplacingMergeTree(ingest_time)
PARTITION BY toYYYYMM(event_time)
ORDER BY (event_id)
TTL event_time + INTERVAL 30 DAY
SETTINGS index_granularity = 8192;

-- 实时告警：飙升检测结果，供监控大屏与告警通道消费
CREATE TABLE IF NOT EXISTS rt.rt_surge_alert
(
    alert_id            String                  COMMENT '告警 ID（实体 + 窗口，幂等）',
    entity_type         LowCardinality(String)  COMMENT '实体类型',
    entity_id           UInt64                  COMMENT '实体 ID',
    title               String                  COMMENT '标题',
    window_start        DateTime                COMMENT '窗口起始',
    window_end          DateTime                COMMENT '窗口结束',
    baseline_popularity Float64                 COMMENT '基线热度',
    current_popularity  Float64                 COMMENT '当前热度',
    growth_ratio        Float64                 COMMENT '涨幅（当前 / 基线）',
    alert_time          DateTime                COMMENT '告警时间'
)
ENGINE = ReplacingMergeTree(alert_time)
PARTITION BY toYYYYMM(alert_time)
ORDER BY (alert_id)
TTL alert_time + INTERVAL 30 DAY
SETTINGS index_granularity = 8192;
