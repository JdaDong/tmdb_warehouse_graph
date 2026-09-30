-- V5 明细层（DWD）：桥接表
--
-- 电影与类型 / 公司 / 关键词 / 国家都是多对多关系。若直接把它们拼成宽表列，
-- 维度成员变化就要改表结构；桥接表把多值关系拆成行，配合字典表做"按任意成员筛选"的查询。
-- 粒度：(movie_id, 成员 ID) 一行，ReplacingMergeTree(load_time) 保证重跑幂等。

CREATE TABLE IF NOT EXISTS dwd.bridge_movie_genre
(
    movie_id        UInt64              COMMENT '电影 ID',
    genre_id        UInt64              COMMENT '类型 ID',
    genre_name      String              COMMENT '类型名称（冗余，避免报表回表）',
    load_time       DateTime            COMMENT '装载时间'
)
ENGINE = ReplacingMergeTree(load_time)
ORDER BY (movie_id, genre_id)
SETTINGS index_granularity = 8192;

CREATE TABLE IF NOT EXISTS dwd.bridge_movie_company
(
    movie_id        UInt64              COMMENT '电影 ID',
    company_id      UInt64              COMMENT '公司 ID',
    company_name    String              COMMENT '公司名称（冗余）',
    load_time       DateTime            COMMENT '装载时间'
)
ENGINE = ReplacingMergeTree(load_time)
ORDER BY (movie_id, company_id)
SETTINGS index_granularity = 8192;

CREATE TABLE IF NOT EXISTS dwd.bridge_movie_keyword
(
    movie_id        UInt64              COMMENT '电影 ID',
    keyword_id      UInt64              COMMENT '关键词 ID',
    keyword_name    String              COMMENT '关键词名称（冗余）',
    load_time       DateTime            COMMENT '装载时间'
)
ENGINE = ReplacingMergeTree(load_time)
ORDER BY (movie_id, keyword_id)
SETTINGS index_granularity = 8192;

CREATE TABLE IF NOT EXISTS dwd.bridge_movie_country
(
    movie_id        UInt64              COMMENT '电影 ID',
    country_code    LowCardinality(String) COMMENT '出品国家地区',
    load_time       DateTime            COMMENT '装载时间'
)
ENGINE = ReplacingMergeTree(load_time)
ORDER BY (movie_id, country_code)
SETTINGS index_granularity = 8192;

CREATE TABLE IF NOT EXISTS dwd.bridge_tv_genre
(
    tv_id           UInt64              COMMENT '剧集 ID',
    genre_id        UInt64              COMMENT '类型 ID',
    genre_name      String              COMMENT '类型名称（冗余）',
    load_time       DateTime            COMMENT '装载时间'
)
ENGINE = ReplacingMergeTree(load_time)
ORDER BY (tv_id, genre_id)
SETTINGS index_granularity = 8192;

CREATE TABLE IF NOT EXISTS dwd.bridge_tv_network
(
    tv_id           UInt64              COMMENT '剧集 ID',
    network_id      UInt64              COMMENT '播出平台 ID',
    network_name    String              COMMENT '平台名称（冗余）',
    load_time       DateTime            COMMENT '装载时间'
)
ENGINE = ReplacingMergeTree(load_time)
ORDER BY (tv_id, network_id)
SETTINGS index_granularity = 8192;
