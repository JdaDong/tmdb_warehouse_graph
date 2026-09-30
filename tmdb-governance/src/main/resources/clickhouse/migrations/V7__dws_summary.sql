-- V7 汇总层（DWS）：主题宽表
--
-- 全部使用 ReplacingMergeTree(load_time) + 显式业务主键，
-- 便于离线任务"按 (维度, dt) 整体重算后覆盖写入"，重跑结果一致。

CREATE TABLE IF NOT EXISTS dws.dws_movie_metric_1d
(
    dt                  Date            COMMENT '统计日期',
    movie_id            UInt64          COMMENT '电影 ID',
    title               String          COMMENT '标题',
    release_year        Nullable(UInt16) COMMENT '上映年份',
    popularity          Float64         COMMENT '当日热度',
    popularity_wow      Float64         COMMENT '热度周环比变化率',
    vote_average        Float64         COMMENT '评分',
    vote_count          UInt32          COMMENT '评分人数',
    revenue             Nullable(UInt64) COMMENT '票房',
    budget              Nullable(UInt64) COMMENT '预算',
    roi                 Nullable(Float64) COMMENT '投入产出比（revenue/budget）',
    cast_count          UInt32          COMMENT '演员人数',
    crew_count          UInt32          COMMENT '剧组人数',
    load_time           DateTime        COMMENT '装载时间'
)
ENGINE = ReplacingMergeTree(load_time)
PARTITION BY toYYYYMM(dt)
ORDER BY (dt, movie_id)
TTL dt + INTERVAL 730 DAY TO VOLUME 'cold'
SETTINGS index_granularity = 8192, storage_policy = 'hot_cold';

CREATE TABLE IF NOT EXISTS dws.dws_genre_year_metric
(
    dt                  Date            COMMENT '统计日期',
    genre_id            UInt64          COMMENT '类型 ID',
    genre_name          String          COMMENT '类型名称',
    release_year        UInt16          COMMENT '上映年份',
    movie_count         UInt32          COMMENT '影片数',
    avg_popularity      Float64         COMMENT '平均热度',
    avg_vote_average    Float64         COMMENT '平均评分',
    total_revenue       Nullable(UInt64) COMMENT '总票房',
    load_time           DateTime        COMMENT '装载时间'
)
ENGINE = ReplacingMergeTree(load_time)
PARTITION BY toYYYYMM(dt)
ORDER BY (dt, genre_id, release_year)
TTL dt + INTERVAL 730 DAY TO VOLUME 'cold'
SETTINGS index_granularity = 8192, storage_policy = 'hot_cold';

CREATE TABLE IF NOT EXISTS dws.dws_person_career
(
    dt                  Date            COMMENT '统计日期',
    person_id           UInt64          COMMENT '人物 ID',
    person_name         String          COMMENT '姓名',
    known_for_department LowCardinality(String) COMMENT '主要职能',
    work_count          UInt32          COMMENT '作品数',
    as_director_count   UInt32          COMMENT '担任导演次数',
    as_cast_count       UInt32          COMMENT '出演次数',
    first_work_year     Nullable(UInt16) COMMENT '首部作品年份',
    latest_work_year    Nullable(UInt16) COMMENT '最近作品年份',
    avg_vote_average    Float64         COMMENT '作品平均评分',
    popularity          Float64         COMMENT '人物热度',
    load_time           DateTime        COMMENT '装载时间'
)
ENGINE = ReplacingMergeTree(load_time)
PARTITION BY toYYYYMM(dt)
ORDER BY (dt, person_id)
TTL dt + INTERVAL 730 DAY TO VOLUME 'cold'
SETTINGS index_granularity = 8192, storage_policy = 'hot_cold';

CREATE TABLE IF NOT EXISTS dws.dws_company_finance
(
    dt                  Date            COMMENT '统计日期',
    company_id          UInt64          COMMENT '公司 ID',
    company_name        String          COMMENT '公司名称',
    movie_count         UInt32          COMMENT '参与影片数',
    total_budget        Nullable(UInt64) COMMENT '总预算',
    total_revenue       Nullable(UInt64) COMMENT '总票房',
    avg_roi             Nullable(Float64) COMMENT '平均投入产出比',
    avg_vote_average    Float64         COMMENT '平均评分',
    load_time           DateTime        COMMENT '装载时间'
)
ENGINE = ReplacingMergeTree(load_time)
PARTITION BY toYYYYMM(dt)
ORDER BY (dt, company_id)
TTL dt + INTERVAL 730 DAY TO VOLUME 'cold'
SETTINGS index_granularity = 8192, storage_policy = 'hot_cold';

CREATE TABLE IF NOT EXISTS dws.dws_country_year_metric
(
    dt                  Date                    COMMENT '统计日期',
    country_code        LowCardinality(String)  COMMENT '出品国家地区',
    release_year        UInt16                  COMMENT '上映年份',
    movie_count         UInt32                  COMMENT '影片数',
    total_revenue       Nullable(UInt64)        COMMENT '总票房',
    avg_vote_average    Float64                 COMMENT '平均评分',
    load_time           DateTime                COMMENT '装载时间'
)
ENGINE = ReplacingMergeTree(load_time)
PARTITION BY toYYYYMM(dt)
ORDER BY (dt, country_code, release_year)
TTL dt + INTERVAL 730 DAY TO VOLUME 'cold'
SETTINGS index_granularity = 8192, storage_policy = 'hot_cold';

-- 图谱分析结果的落库表：Neo4j GDS 计算结果（PageRank / 社区发现等）回流后按 dt 保存，
-- 让图分析结果也能进入常规 BI 流程。
CREATE TABLE IF NOT EXISTS dws.dws_movie_graph_metric
(
    dt                  Date            COMMENT '计算日期',
    movie_id            UInt64          COMMENT '电影 ID',
    pagerank            Float64         COMMENT 'PageRank（影响力）',
    community_id        Nullable(Int64) COMMENT '社区编号（Louvain）',
    degree              UInt32          COMMENT '关联度数（合作网络中的边数）',
    betweenness         Float64         COMMENT '中介中心性',
    load_time           DateTime        COMMENT '装载时间'
)
ENGINE = ReplacingMergeTree(load_time)
PARTITION BY toYYYYMM(dt)
ORDER BY (dt, movie_id)
TTL dt + INTERVAL 730 DAY TO VOLUME 'cold'
SETTINGS index_granularity = 8192, storage_policy = 'hot_cold';
