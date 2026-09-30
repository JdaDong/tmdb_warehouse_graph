-- V8 应用层（ADS）：面向报表与接口的最终结果表
--
-- 特点：字段已按消费方需求裁剪，可直接被 BI / 接口查询；
-- 全部保留历史分区（不设删除型 TTL），只做冷热分层。

CREATE TABLE IF NOT EXISTS ads.ads_top_movie
(
    dt                  Date            COMMENT '数据日期',
    rank                UInt32          COMMENT '排名（按热度）',
    movie_id            UInt64          COMMENT '电影 ID',
    title               String          COMMENT '标题',
    release_year        Nullable(UInt16) COMMENT '上映年份',
    popularity          Float64         COMMENT '热度',
    vote_average        Float64         COMMENT '评分',
    vote_count          UInt32          COMMENT '评分人数',
    revenue             Nullable(UInt64) COMMENT '票房',
    roi                 Nullable(Float64) COMMENT '投入产出比',
    load_time           DateTime        COMMENT '装载时间'
)
ENGINE = ReplacingMergeTree(load_time)
PARTITION BY toYYYYMM(dt)
ORDER BY (dt, rank)
TTL dt + INTERVAL 1095 DAY TO VOLUME 'cold'
SETTINGS index_granularity = 8192, storage_policy = 'hot_cold';

CREATE TABLE IF NOT EXISTS ads.ads_genre_trend
(
    dt                  Date            COMMENT '数据日期',
    genre_id            UInt64          COMMENT '类型 ID',
    genre_name          String          COMMENT '类型名称',
    movie_count         UInt32          COMMENT '影片数',
    avg_popularity      Float64         COMMENT '平均热度',
    avg_vote_average    Float64         COMMENT '平均评分',
    popularity_wow      Float64         COMMENT '热度周环比变化率',
    load_time           DateTime        COMMENT '装载时间'
)
ENGINE = ReplacingMergeTree(load_time)
PARTITION BY toYYYYMM(dt)
ORDER BY (dt, genre_id)
TTL dt + INTERVAL 1095 DAY TO VOLUME 'cold'
SETTINGS index_granularity = 8192, storage_policy = 'hot_cold';

CREATE TABLE IF NOT EXISTS ads.ads_roi_ranking
(
    dt                  Date            COMMENT '数据日期',
    rank                UInt32          COMMENT '排名（按 ROI）',
    movie_id            UInt64          COMMENT '电影 ID',
    title               String          COMMENT '标题',
    budget              Nullable(UInt64) COMMENT '预算',
    revenue             Nullable(UInt64) COMMENT '票房',
    roi                 Nullable(Float64) COMMENT '投入产出比',
    vote_average        Float64         COMMENT '评分',
    load_time           DateTime        COMMENT '装载时间'
)
ENGINE = ReplacingMergeTree(load_time)
PARTITION BY toYYYYMM(dt)
ORDER BY (dt, rank)
TTL dt + INTERVAL 1095 DAY TO VOLUME 'cold'
SETTINGS index_granularity = 8192, storage_policy = 'hot_cold';

-- 人物影响力：由图谱 PageRank 与作品表现综合计算，回写到 OLAP 供推荐 / 选角分析使用
CREATE TABLE IF NOT EXISTS ads.ads_person_influence
(
    dt                  Date            COMMENT '数据日期',
    rank                UInt32          COMMENT '排名',
    person_id           UInt64          COMMENT '人物 ID',
    person_name         String          COMMENT '姓名',
    known_for_department LowCardinality(String) COMMENT '主要职能',
    influence_score     Float64         COMMENT '影响力综合分',
    pagerank            Float64         COMMENT '图谱 PageRank',
    work_count          UInt32          COMMENT '作品数',
    avg_vote_average    Float64         COMMENT '作品平均评分',
    load_time           DateTime        COMMENT '装载时间'
)
ENGINE = ReplacingMergeTree(load_time)
PARTITION BY toYYYYMM(dt)
ORDER BY (dt, rank)
TTL dt + INTERVAL 1095 DAY TO VOLUME 'cold'
SETTINGS index_granularity = 8192, storage_policy = 'hot_cold';

-- 实时榜单结果（Flink 计算后写入，供接口直接查询最新榜单）
CREATE TABLE IF NOT EXISTS ads.ads_realtime_trending
(
    window_start        DateTime                COMMENT '窗口起始时间',
    window_end          DateTime                COMMENT '窗口结束时间',
    entity_type         LowCardinality(String)  COMMENT 'movie / tv / person',
    entity_id           UInt64                  COMMENT '实体 ID',
    title               String                  COMMENT '标题 / 姓名',
    rank                UInt32                  COMMENT '窗口内名次',
    avg_popularity      Float64                 COMMENT '窗口内平均热度',
    max_popularity      Float64                 COMMENT '窗口内最高热度',
    observations        UInt32                  COMMENT '窗口内观测次数',
    load_time           DateTime                COMMENT '装载时间'
)
ENGINE = ReplacingMergeTree(load_time)
PARTITION BY toYYYYMM(window_start)
ORDER BY (window_start, entity_type, rank)
TTL window_start + INTERVAL 180 DAY
SETTINGS index_granularity = 8192;
