-- V2 贴源层（ODS）：实体原始报文
--
-- 设计要点：
--   - payload 保留 TMDB 原始 JSON，口径变更时可重算，不需要重新采集；
--   - schema_version 记录外层结构版本，便于演进时兼容解析；
--   - ReplacingMergeTree(ingest_time)：同一 (dt, entity_id) 重复写入时保留最新一条，
--     让采集重试 / 重跑天然幂等；
--   - TTL：90 天下沉到冷盘（storage_policy = hot_cold），不删除，保证可回溯；
--   - 分区按月（toYYYYMM(dt)），与生命周期治理的分区级操作对齐。

CREATE TABLE IF NOT EXISTS ods.ods_movie_raw
(
    dt              Date                COMMENT '业务分区日期',
    entity_id       UInt64              COMMENT 'TMDB 电影 ID',
    payload         String              COMMENT 'TMDB 原始 JSON',
    ingest_time     DateTime            COMMENT '采集时间',
    schema_version  UInt16 DEFAULT 1    COMMENT '记录结构版本',
    source          LowCardinality(String) DEFAULT 'tmdb.details' COMMENT '数据来源'
)
ENGINE = ReplacingMergeTree(ingest_time)
PARTITION BY toYYYYMM(dt)
ORDER BY (dt, entity_id)
TTL dt + INTERVAL 90 DAY TO VOLUME 'cold'
SETTINGS index_granularity = 8192, storage_policy = 'hot_cold';

CREATE TABLE IF NOT EXISTS ods.ods_tv_raw
(
    dt              Date                COMMENT '业务分区日期',
    entity_id       UInt64              COMMENT 'TMDB 剧集 ID',
    payload         String              COMMENT 'TMDB 原始 JSON',
    ingest_time     DateTime            COMMENT '采集时间',
    schema_version  UInt16 DEFAULT 1    COMMENT '记录结构版本',
    source          LowCardinality(String) DEFAULT 'tmdb.details' COMMENT '数据来源'
)
ENGINE = ReplacingMergeTree(ingest_time)
PARTITION BY toYYYYMM(dt)
ORDER BY (dt, entity_id)
TTL dt + INTERVAL 90 DAY TO VOLUME 'cold'
SETTINGS index_granularity = 8192, storage_policy = 'hot_cold';

CREATE TABLE IF NOT EXISTS ods.ods_person_raw
(
    dt              Date                COMMENT '业务分区日期',
    entity_id       UInt64              COMMENT 'TMDB 人物 ID',
    payload         String              COMMENT 'TMDB 原始 JSON',
    ingest_time     DateTime            COMMENT '采集时间',
    schema_version  UInt16 DEFAULT 1    COMMENT '记录结构版本',
    source          LowCardinality(String) DEFAULT 'tmdb.details' COMMENT '数据来源'
)
ENGINE = ReplacingMergeTree(ingest_time)
PARTITION BY toYYYYMM(dt)
ORDER BY (dt, entity_id)
TTL dt + INTERVAL 90 DAY TO VOLUME 'cold'
SETTINGS index_granularity = 8192, storage_policy = 'hot_cold';

CREATE TABLE IF NOT EXISTS ods.ods_company_raw
(
    dt              Date                COMMENT '业务分区日期',
    entity_id       UInt64              COMMENT 'TMDB 公司 ID',
    payload         String              COMMENT 'TMDB 原始 JSON',
    ingest_time     DateTime            COMMENT '采集时间',
    schema_version  UInt16 DEFAULT 1    COMMENT '记录结构版本',
    source          LowCardinality(String) DEFAULT 'tmdb.details' COMMENT '数据来源'
)
ENGINE = ReplacingMergeTree(ingest_time)
PARTITION BY toYYYYMM(dt)
ORDER BY (dt, entity_id)
TTL dt + INTERVAL 90 DAY TO VOLUME 'cold'
SETTINGS index_granularity = 8192, storage_policy = 'hot_cold';

CREATE TABLE IF NOT EXISTS ods.ods_collection_raw
(
    dt              Date                COMMENT '业务分区日期',
    entity_id       UInt64              COMMENT 'TMDB 系列 ID',
    payload         String              COMMENT 'TMDB 原始 JSON',
    ingest_time     DateTime            COMMENT '采集时间',
    schema_version  UInt16 DEFAULT 1    COMMENT '记录结构版本',
    source          LowCardinality(String) DEFAULT 'tmdb.details' COMMENT '数据来源'
)
ENGINE = ReplacingMergeTree(ingest_time)
PARTITION BY toYYYYMM(dt)
ORDER BY (dt, entity_id)
TTL dt + INTERVAL 90 DAY TO VOLUME 'cold'
SETTINGS index_granularity = 8192, storage_policy = 'hot_cold';

-- 类型 / 国家 / 语言等字典：数据量小且不随业务日期变化，用 ReplacingMergeTree 按主键去重即可
CREATE TABLE IF NOT EXISTS ods.ods_genre_raw
(
    dt              Date                COMMENT '采集日期',
    entity_id       UInt64              COMMENT '类型 ID',
    payload         String              COMMENT 'TMDB 原始 JSON',
    ingest_time     DateTime            COMMENT '采集时间',
    schema_version  UInt16 DEFAULT 1    COMMENT '记录结构版本',
    source          LowCardinality(String) DEFAULT 'tmdb.reference' COMMENT '数据来源'
)
ENGINE = ReplacingMergeTree(ingest_time)
PARTITION BY toYYYYMM(dt)
ORDER BY (entity_id)
TTL dt + INTERVAL 365 DAY
SETTINGS index_granularity = 8192;
