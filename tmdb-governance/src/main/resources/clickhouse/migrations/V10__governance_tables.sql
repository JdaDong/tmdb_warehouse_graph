-- V10 治理层（governance）：治理执行结果落库
--
-- schema_migrations 由迁移器自动创建（代码在 SchemaMigrator#ensureHistoryTable），
-- 这里只放治理引擎各模块的结果表。全部为"每次运行一份快照"，便于回看趋势与审计。

-- 元数据快照：system.tables / system.columns 的定期采样，用于表结构变更检测与资产盘点
CREATE TABLE IF NOT EXISTS governance.metadata_snapshot
(
    captured_at         DateTime            COMMENT '采集时间',
    database            LowCardinality(String) COMMENT '库名',
    table               String              COMMENT '表名',
    engine              LowCardinality(String) COMMENT '表引擎',
    partition_key       String              COMMENT '分区键',
    sorting_key         String              COMMENT '排序键',
    total_rows          Nullable(UInt64)    COMMENT '行数',
    total_bytes         Nullable(UInt64)    COMMENT '字节数',
    parts_count         Nullable(UInt64)    COMMENT 'part 数',
    comment             String              COMMENT '表注释'
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(captured_at)
ORDER BY (captured_at, database, table)
TTL captured_at + INTERVAL 180 DAY
SETTINGS index_granularity = 8192;

-- 字段级元数据快照
CREATE TABLE IF NOT EXISTS governance.metadata_column_snapshot
(
    captured_at         DateTime            COMMENT '采集时间',
    database            LowCardinality(String) COMMENT '库名',
    table               String              COMMENT '表名',
    column              String              COMMENT '字段名',
    column_type         String              COMMENT '字段类型',
    position            UInt32              COMMENT '字段序号',
    comment             String              COMMENT '字段注释',
    is_nullable         UInt8               COMMENT '是否可为空',
    is_in_sorting_key   UInt8               COMMENT '是否在排序键中'
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(captured_at)
ORDER BY (captured_at, database, table, position)
TTL captured_at + INTERVAL 180 DAY
SETTINGS index_granularity = 8192;

-- 数据标准检查结果：命名规范、注释完备度、必备字段、类型规范
CREATE TABLE IF NOT EXISTS governance.standard_check_result
(
    checked_at          DateTime            COMMENT '检查时间',
    database            LowCardinality(String) COMMENT '库名',
    table               String              COMMENT '表名',
    column              String              COMMENT '字段名（表级检查时为空）',
    rule                LowCardinality(String) COMMENT '规则名',
    severity            LowCardinality(String) COMMENT 'BLOCKER / WARN / INFO',
    passed              UInt8               COMMENT '是否通过',
    message             String              COMMENT '说明'
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(checked_at)
ORDER BY (checked_at, database, table, rule)
TTL checked_at + INTERVAL 180 DAY
SETTINGS index_granularity = 8192;

-- 数据质量检查结果：唯一性、空值、值域、及时性、行数波动、参照完整性等
CREATE TABLE IF NOT EXISTS governance.dq_result
(
    checked_at          DateTime            COMMENT '检查时间',
    rule_id             String              COMMENT '规则 ID',
    database            LowCardinality(String) COMMENT '库名',
    table               String              COMMENT '表名',
    column              String              COMMENT '字段名',
    severity            LowCardinality(String) COMMENT 'BLOCKER / WARN / INFO',
    passed              UInt8               COMMENT '是否通过',
    metric_value        Nullable(Float64)   COMMENT '实际指标值',
    threshold_value     Nullable(Float64)   COMMENT '阈值',
    sample_data         String              COMMENT '样例数据（脱敏后）',
    message             String              COMMENT '说明'
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(checked_at)
ORDER BY (checked_at, rule_id)
TTL checked_at + INTERVAL 365 DAY
SETTINGS index_granularity = 8192;

-- 血缘边：来源表 / 字段 -> 目标表 / 字段，支撑影响分析与上游追溯
CREATE TABLE IF NOT EXISTS governance.lineage_edge
(
    captured_at         DateTime            COMMENT '采集时间',
    src_database        LowCardinality(String) COMMENT '上游库',
    src_table           String              COMMENT '上游表',
    src_column          String              COMMENT '上游字段（表级血缘为空）',
    dst_database        LowCardinality(String) COMMENT '下游库',
    dst_table           String              COMMENT '下游表',
    dst_column          String              COMMENT '下游字段',
    job                 String              COMMENT '产生该血缘的作业',
    edge_type           LowCardinality(String) COMMENT 'SQL_PARSED / DECLARED / QUERY_LOG'
)
ENGINE = ReplacingMergeTree(captured_at)
PARTITION BY toYYYYMM(captured_at)
ORDER BY (src_database, src_table, dst_database, dst_table, src_column, dst_column)
TTL captured_at + INTERVAL 365 DAY
SETTINGS index_granularity = 8192;

-- 生命周期策略执行记录：TTL / 冷热迁移的"计划 vs 实际"
CREATE TABLE IF NOT EXISTS governance.lifecycle_execution
(
    executed_at         DateTime            COMMENT '执行时间',
    database            LowCardinality(String) COMMENT '库名',
    table               String              COMMENT '表名',
    policy              LowCardinality(String) COMMENT '策略名',
    action              LowCardinality(String) COMMENT 'SET_TTL / MOVE_TO_VOLUME / DROP_PARTITION',
    status              LowCardinality(String) COMMENT 'APPLIED / SKIPPED / FAILED',
    detail              String              COMMENT '执行详情（含 DDL）'
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(executed_at)
ORDER BY (executed_at, database, table)
TTL executed_at + INTERVAL 365 DAY
SETTINGS index_granularity = 8192;

-- 成本 / 慢查询分析：由 system.query_log 聚合而来，定位最耗资源的查询与用户
CREATE TABLE IF NOT EXISTS governance.query_cost_report
(
    reported_at         DateTime            COMMENT '生成时间',
    window_start        DateTime            COMMENT '统计窗口起点',
    window_end          DateTime            COMMENT '统计窗口终点',
    user                LowCardinality(String) COMMENT '查询用户',
    query_kind          LowCardinality(String) COMMENT '查询类型',
    query_count         UInt64              COMMENT '查询次数',
    total_duration_ms   UInt64              COMMENT '总耗时（毫秒）',
    avg_duration_ms     Float64             COMMENT '平均耗时',
    max_memory_bytes    UInt64              COMMENT '峰值内存',
    read_rows           UInt64              COMMENT '读取行数',
    sample_query        String              COMMENT '代表性查询（截断）'
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(reported_at)
ORDER BY (reported_at, user, query_kind)
TTL reported_at + INTERVAL 180 DAY
SETTINGS index_granularity = 8192;
