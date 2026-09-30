-- V2 测试用第二张表（含多行与注释中的分号）
-- 注释里的 ; 不应被当作语句结束
CREATE TABLE IF NOT EXISTS tmdbwh_test.t_two
(
    dt     Date   COMMENT '日期',
    cnt    UInt64 COMMENT '计数'
)
ENGINE = ReplacingMergeTree()
PARTITION BY toYYYYMM(dt)
ORDER BY (dt);
