-- V11 物化视图：实时预聚合
--
-- 作用：把"热度按小时 / 按天聚合"这类高频查询在<b>写入时</b>算好，查询直接读聚合结果，
-- 避免每次扫描明细。
-- 约定：目标表使用 AggregatingMergeTree + AggregateFunction/SimpleAggregateFunction；
--       物化视图只写入 State，最终值在查询时用对应的 Merge 函数读取，例如：
--         SELECT entity_id, avgMerge(avg_popularity), countMerge(observations)
--         FROM rt.rt_popularity_hourly GROUP BY entity_id;
-- 依赖：V9 建立的 rt.rt_popularity_event（明细表）。

CREATE TABLE IF NOT EXISTS rt.rt_popularity_hourly
(
    hour_start          DateTime                            COMMENT '小时起点',
    entity_type         LowCardinality(String)              COMMENT '实体类型',
    entity_id           UInt64                              COMMENT '实体 ID',
    observations        AggregateFunction(count, UInt64)    COMMENT '观测次数',
    avg_popularity      AggregateFunction(avg, Float64)     COMMENT '平均热度',
    max_popularity      SimpleAggregateFunction(max, Float64) COMMENT '最高热度',
    min_rank            SimpleAggregateFunction(min, UInt32) COMMENT '窗口内最好名次'
)
ENGINE = AggregatingMergeTree
PARTITION BY toYYYYMM(hour_start)
ORDER BY (hour_start, entity_type, entity_id)
TTL hour_start + INTERVAL 30 DAY
SETTINGS index_granularity = 8192;

CREATE MATERIALIZED VIEW IF NOT EXISTS rt.mv_popularity_hourly TO rt.rt_popularity_hourly AS
SELECT
    toStartOfHour(event_time)       AS hour_start,
    entity_type                     AS entity_type,
    entity_id                       AS entity_id,
    countState(toUInt64(1))         AS observations,
    avgState(popularity)            AS avg_popularity,
    maxSimpleState(popularity)      AS max_popularity,
    minSimpleState(rank)            AS min_rank
FROM rt.rt_popularity_event
GROUP BY hour_start, entity_type, entity_id;

-- 按天聚合：保留 180 天，供"热度日趋势"报表直接使用
CREATE TABLE IF NOT EXISTS rt.rt_popularity_daily
(
    dt                  Date                                COMMENT '统计日期',
    entity_type         LowCardinality(String)              COMMENT '实体类型',
    entity_id           UInt64                              COMMENT '实体 ID',
    observations        AggregateFunction(count, UInt64)    COMMENT '观测次数',
    avg_popularity      AggregateFunction(avg, Float64)     COMMENT '平均热度',
    max_popularity      SimpleAggregateFunction(max, Float64) COMMENT '最高热度'
)
ENGINE = AggregatingMergeTree
PARTITION BY toYYYYMM(dt)
ORDER BY (dt, entity_type, entity_id)
TTL dt + INTERVAL 180 DAY
SETTINGS index_granularity = 8192;

CREATE MATERIALIZED VIEW IF NOT EXISTS rt.mv_popularity_daily TO rt.rt_popularity_daily AS
SELECT
    toDate(event_time)              AS dt,
    entity_type                     AS entity_type,
    entity_id                       AS entity_id,
    countState(toUInt64(1))         AS observations,
    avgState(popularity)            AS avg_popularity,
    maxSimpleState(popularity)      AS max_popularity
FROM rt.rt_popularity_event
GROUP BY dt, entity_type, entity_id;

-- 告警去重：同一实体同一小时内只汇总一次，避免重复告警刷屏
CREATE TABLE IF NOT EXISTS rt.rt_alert_dedup
(
    alert_hour          DateTime                        COMMENT '告警归属小时',
    entity_type         LowCardinality(String)          COMMENT '实体类型',
    entity_id           UInt64                          COMMENT '实体 ID',
    alerts              AggregateFunction(count, UInt64) COMMENT '告警次数',
    max_growth_ratio    SimpleAggregateFunction(max, Float64) COMMENT '窗口内最大涨幅'
)
ENGINE = AggregatingMergeTree
PARTITION BY toYYYYMM(alert_hour)
ORDER BY (alert_hour, entity_type, entity_id)
TTL alert_hour + INTERVAL 30 DAY
SETTINGS index_granularity = 8192;

CREATE MATERIALIZED VIEW IF NOT EXISTS rt.mv_alert_dedup TO rt.rt_alert_dedup AS
SELECT
    toStartOfHour(alert_time)       AS alert_hour,
    entity_type                     AS entity_type,
    entity_id                       AS entity_id,
    countState(toUInt64(1))         AS alerts,
    maxSimpleState(growth_ratio)    AS max_growth_ratio
FROM rt.rt_surge_alert
GROUP BY alert_hour, entity_type, entity_id;
