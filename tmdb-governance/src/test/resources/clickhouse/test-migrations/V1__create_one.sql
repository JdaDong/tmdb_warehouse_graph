-- V1 测试用建表
CREATE DATABASE IF NOT EXISTS tmdbwh_test;
CREATE TABLE IF NOT EXISTS tmdbwh_test.t_one
(
    id     UInt64 COMMENT '主键',
    name   String COMMENT '名称'
)
ENGINE = ReplacingMergeTree()
ORDER BY id;
