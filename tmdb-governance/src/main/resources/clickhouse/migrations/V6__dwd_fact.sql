-- V6 明细层（DWD）：事实表
--
-- fact_movie_credit：演职员事实，粒度为 credit_id（同一人可担任多个职位），
--   关联 dim_movie / dim_person 时按 valid_from / valid_to 取当时有效版本。
-- fact_movie_daily_snapshot：周期快照事实，每天一条（dt + movie_id 唯一），
--   用于热度 / 评分随时间变化的趋势分析；重复装载按 load_time 去重。
-- fact_movie_release：分国家上映事实（一个电影 × 国家 × 上映类型 一行）。

CREATE TABLE IF NOT EXISTS dwd.fact_movie_credit
(
    dt              Date                    COMMENT '业务分区日期',
    credit_id       String                  COMMENT '演职员关系 ID（唯一粒度）',
    movie_id        UInt64                  COMMENT '电影 ID',
    person_id       UInt64                  COMMENT '人物 ID',
    credit_type     LowCardinality(String)  COMMENT 'cast / crew',
    department      LowCardinality(String)  COMMENT '职能部门（crew 有效）',
    job             LowCardinality(String)  COMMENT '具体职位（如 Director）',
    character_name  String                  COMMENT '角色名（cast 有效）',
    cast_order      Nullable(UInt32)        COMMENT '番位（0 = 主演）',
    is_director     UInt8                   COMMENT '是否导演（派生，便于筛选）',
    load_time       DateTime                COMMENT '装载时间'
)
ENGINE = ReplacingMergeTree(load_time)
PARTITION BY toYYYYMM(dt)
ORDER BY (dt, credit_id)
TTL dt + INTERVAL 365 DAY TO VOLUME 'cold'
SETTINGS index_granularity = 8192, storage_policy = 'hot_cold';

CREATE TABLE IF NOT EXISTS dwd.fact_movie_daily_snapshot
(
    dt              Date                COMMENT '快照日期',
    movie_id        UInt64              COMMENT '电影 ID',
    title           String              COMMENT '标题（快照时点值）',
    popularity      Float64             COMMENT '热度',
    vote_average    Float64             COMMENT '评分',
    vote_count      UInt32              COMMENT '评分人数',
    revenue         Nullable(UInt64)    COMMENT '票房（快照时点值）',
    release_date    Nullable(Date)      COMMENT '上映日期',
    load_time       DateTime            COMMENT '装载时间'
)
ENGINE = ReplacingMergeTree(load_time)
PARTITION BY toYYYYMM(dt)
ORDER BY (dt, movie_id)
TTL dt + INTERVAL 365 DAY TO VOLUME 'cold'
SETTINGS index_granularity = 8192, storage_policy = 'hot_cold';

CREATE TABLE IF NOT EXISTS dwd.fact_movie_release
(
    dt              Date                    COMMENT '业务分区日期',
    movie_id        UInt64                  COMMENT '电影 ID',
    country_code    LowCardinality(String)  COMMENT '上映国家地区',
    release_type    LowCardinality(String)  COMMENT '上映类型：首映/院线/数字/实体/电视',
    release_date    Nullable(DateTime)      COMMENT '上映时间（含时区信息）',
    certification   String                  COMMENT '分级（如 PG-13）',
    load_time       DateTime                COMMENT '装载时间'
)
ENGINE = ReplacingMergeTree(load_time)
PARTITION BY toYYYYMM(dt)
ORDER BY (dt, movie_id, country_code)
TTL dt + INTERVAL 365 DAY TO VOLUME 'cold'
SETTINGS index_granularity = 8192, storage_policy = 'hot_cold';

-- 变更事实：增量链路的落地记录，供"某实体在某天是否变更过"类分析使用
CREATE TABLE IF NOT EXISTS dwd.fact_change_event
(
    dt              Date                    COMMENT '业务分区日期',
    event_id        String                  COMMENT '事件 ID',
    entity_type     LowCardinality(String)  COMMENT 'movie / tv / person',
    entity_id       UInt64                  COMMENT '实体 ID',
    window_start    Nullable(Date)          COMMENT '变更查询窗口起点',
    window_end      Nullable(Date)          COMMENT '变更查询窗口终点',
    event_time      DateTime                COMMENT '事件时间',
    load_time       DateTime                COMMENT '装载时间'
)
ENGINE = ReplacingMergeTree(load_time)
PARTITION BY toYYYYMM(dt)
ORDER BY (dt, event_id)
TTL dt + INTERVAL 180 DAY
SETTINGS index_granularity = 8192;
