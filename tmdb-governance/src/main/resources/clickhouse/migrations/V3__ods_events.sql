-- V3 贴源层（ODS）：变更与热度事件
--
-- 这两张表是实时链路的落湖备份：Flink 写 rt 库的同时写这里，
-- 一旦实时作业出错，可用离线链路按 event_id 去重后补算，保证"实时不准、离线兜底"。
--   - ReplacingMergeTree(ingest_time)：按 (dt, event_id) 去重；
--   - 分区按天（量级小于实体表，且需要按天快速清理）。

CREATE TABLE IF NOT EXISTS ods.ods_change_event
(
    dt              Date                    COMMENT '业务分区日期',
    event_id        String                  COMMENT '事件 ID（内容哈希，幂等基础）',
    entity_type     LowCardinality(String)  COMMENT 'movie / tv / person',
    entity_id       UInt64                  COMMENT '实体 ID',
    event_time      DateTime                COMMENT '业务事件时间',
    payload         String                  COMMENT '事件 JSON（EventEnvelope）',
    ingest_time     DateTime                COMMENT '进入平台时间',
    schema_version  UInt16 DEFAULT 1        COMMENT '记录结构版本'
)
ENGINE = ReplacingMergeTree(ingest_time)
PARTITION BY toYYYYMM(dt)
ORDER BY (dt, event_id)
TTL dt + INTERVAL 90 DAY TO VOLUME 'cold'
SETTINGS index_granularity = 8192, storage_policy = 'hot_cold';

CREATE TABLE IF NOT EXISTS ods.ods_popularity_event
(
    dt              Date                    COMMENT '业务分区日期',
    event_id        String                  COMMENT '事件 ID（内容哈希）',
    entity_type     LowCardinality(String)  COMMENT 'movie / tv / person',
    entity_id       UInt64                  COMMENT '实体 ID',
    event_time      DateTime                COMMENT '观测时间',
    payload         String                  COMMENT '事件 JSON（EventEnvelope）',
    ingest_time     DateTime                COMMENT '进入平台时间',
    schema_version  UInt16 DEFAULT 1        COMMENT '记录结构版本'
)
ENGINE = ReplacingMergeTree(ingest_time)
PARTITION BY toYYYYMM(dt)
ORDER BY (dt, event_id)
TTL dt + INTERVAL 90 DAY TO VOLUME 'cold'
SETTINGS index_granularity = 8192, storage_policy = 'hot_cold';
