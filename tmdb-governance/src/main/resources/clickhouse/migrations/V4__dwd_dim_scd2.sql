-- V4 明细层（DWD）：缓慢变化维 SCD2
--
-- 为什么用 SCD2：TMDB 的 popularity / vote_count / revenue 等字段会持续变化，
-- 若直接覆盖更新，历史事实表会"回溯变形"（昨天的报表今天重跑结果不同）。
-- 因此维度保留完整版本链，事实表关联时按 valid_from / valid_to 取当时有效的版本。
--
-- 版本链约定：
--   - is_current = 1 表示当前有效版本（查询默认过滤条件）；
--   - valid_to = 2099-12-31 表示"尚未失效"；
--   - (entity_id, valid_from) 唯一，重复写入由 ReplacingMergeTree(load_time) 去重。

CREATE TABLE IF NOT EXISTS dwd.dim_movie
(
    movie_sk        UInt64              COMMENT '维度代理键（entity_id + 版本哈希）',
    movie_id        UInt64              COMMENT 'TMDB 电影 ID（自然键）',
    title           String              COMMENT '标题',
    original_title  String              COMMENT '原始标题',
    original_language LowCardinality(String) COMMENT '原始语言',
    overview        String              COMMENT '简介（长文本，不进入宽表）',
    status          LowCardinality(String) COMMENT 'Released / In Production 等',
    release_date    Nullable(Date)      COMMENT '上映日期（可能为空）',
    release_year    Nullable(UInt16)    COMMENT '上映年份，便于按年聚合',
    runtime         Nullable(UInt32)    COMMENT '片长（分钟）',
    budget          Nullable(UInt64)    COMMENT '预算（美元，未知已清洗为 NULL）',
    revenue         Nullable(UInt64)    COMMENT '票房（美元，未知已清洗为 NULL）',
    popularity      Float64             COMMENT '热度',
    vote_average    Float64             COMMENT '评分',
    vote_count      UInt32              COMMENT '评分人数',
    adult           UInt8               COMMENT '是否成人内容',
    collection_id   Nullable(UInt64)    COMMENT '所属系列 ID',
    is_current      UInt8               COMMENT '1 当前有效版本',
    valid_from      DateTime            COMMENT '版本生效时间',
    valid_to        DateTime            COMMENT '版本失效时间（2099-12-31 表示未失效）',
    load_time       DateTime            COMMENT '装载时间（去重版本号）'
)
ENGINE = ReplacingMergeTree(load_time)
PARTITION BY toYYYYMM(valid_from)
ORDER BY (movie_id, valid_from)
SETTINGS index_granularity = 8192;

CREATE TABLE IF NOT EXISTS dwd.dim_person
(
    person_sk       UInt64              COMMENT '维度代理键',
    person_id       UInt64              COMMENT 'TMDB 人物 ID',
    name            String              COMMENT '姓名',
    gender          LowCardinality(String) COMMENT 'unknown / female / male / non-binary',
    birthday        Nullable(Date)      COMMENT '出生日期',
    deathday        Nullable(Date)      COMMENT '逝世日期',
    place_of_birth  String              COMMENT '出生地',
    known_for_department LowCardinality(String) COMMENT '主要职能',
    popularity      Float64             COMMENT '热度',
    adult           UInt8               COMMENT '是否成人内容',
    is_current      UInt8               COMMENT '1 当前有效版本',
    valid_from      DateTime            COMMENT '版本生效时间',
    valid_to        DateTime            COMMENT '版本失效时间',
    load_time       DateTime            COMMENT '装载时间'
)
ENGINE = ReplacingMergeTree(load_time)
PARTITION BY toYYYYMM(valid_from)
ORDER BY (person_id, valid_from)
SETTINGS index_granularity = 8192;

-- 全量维度：不随时间变化（或变化无需保留历史），直接用 ReplacingMergeTree 覆盖
CREATE TABLE IF NOT EXISTS dwd.dim_company
(
    company_id      UInt64              COMMENT 'TMDB 公司 ID',
    name            String              COMMENT '公司名称',
    name_normalized String              COMMENT '规范化名称（去括号后缀等，用于实体合并）',
    origin_country  LowCardinality(String) COMMENT '所属国家地区',
    parent_company_id Nullable(UInt64)  COMMENT '母公司 ID',
    load_time       DateTime            COMMENT '装载时间'
)
ENGINE = ReplacingMergeTree(load_time)
ORDER BY company_id
SETTINGS index_granularity = 8192;

CREATE TABLE IF NOT EXISTS dwd.dim_genre
(
    genre_id        UInt64              COMMENT '类型 ID',
    genre_name      String              COMMENT '类型名称',
    media_type      LowCardinality(String) COMMENT 'movie / tv（两者 ID 空间不同）',
    load_time       DateTime            COMMENT '装载时间'
)
ENGINE = ReplacingMergeTree(load_time)
ORDER BY (media_type, genre_id)
SETTINGS index_granularity = 8192;

CREATE TABLE IF NOT EXISTS dwd.dim_keyword
(
    keyword_id      UInt64              COMMENT '关键词 ID',
    keyword_name    String              COMMENT '关键词名称',
    load_time       DateTime            COMMENT '装载时间'
)
ENGINE = ReplacingMergeTree(load_time)
ORDER BY keyword_id
SETTINGS index_granularity = 8192;

CREATE TABLE IF NOT EXISTS dwd.dim_country
(
    country_code    LowCardinality(String) COMMENT 'ISO 3166-1 alpha-2',
    country_name_en String              COMMENT '英文名称',
    country_name_native String          COMMENT '本地语言名称',
    load_time       DateTime            COMMENT '装载时间'
)
ENGINE = ReplacingMergeTree(load_time)
ORDER BY country_code
SETTINGS index_granularity = 8192;

CREATE TABLE IF NOT EXISTS dwd.dim_language
(
    language_code   LowCardinality(String) COMMENT 'ISO 639-1',
    language_name_en String             COMMENT '英文名称',
    language_name_native String         COMMENT '本地语言名称',
    load_time       DateTime            COMMENT '装载时间'
)
ENGINE = ReplacingMergeTree(load_time)
ORDER BY language_code
SETTINGS index_granularity = 8192;

CREATE TABLE IF NOT EXISTS dwd.dim_collection
(
    collection_id   UInt64              COMMENT '系列 ID',
    name            String              COMMENT '系列名称',
    load_time       DateTime            COMMENT '装载时间'
)
ENGINE = ReplacingMergeTree(load_time)
ORDER BY collection_id
SETTINGS index_granularity = 8192;

-- 日期维：报表按周 / 月 / 季度切分必备，一次性生成多年数据
CREATE TABLE IF NOT EXISTS dwd.dim_date
(
    dt              Date                COMMENT '日期',
    year            UInt16              COMMENT '年',
    quarter         UInt8               COMMENT '季度',
    month           UInt8               COMMENT '月',
    day             UInt8               COMMENT '日',
    week_of_year    UInt8               COMMENT '年内周序',
    day_of_week     UInt8               COMMENT '周内天序（1=周一）',
    is_weekend      UInt8               COMMENT '是否周末'
)
ENGINE = MergeTree
ORDER BY dt
SETTINGS index_granularity = 8192;
