-- V12 生命周期治理视图
--
-- 冷热分层的<b>物理配置</b>（storage_policy = hot_cold）在 deploy/compose/conf/clickhouse/config.d/20-storage.xml 中声明，
-- 各表的 TTL ... TO VOLUME 'cold' 在各自建表脚本中声明。
-- 这里补上"看得见"的部分：让治理引擎与运维可以直接查询分层现状，
-- 而不用每次手写 system.* 查询。
--
-- 注意：字典（DICTIONARY）需要指定 ClickHouse 源地址，单机与集群的写法不同，
-- 因此不放在迁移脚本里硬编码，改由治理引擎按实际连接参数生成，见 docs/05-OLAP数据治理.md。

-- 存储策略与磁盘现状：确认 hot_cold 策略已生效、冷盘指向对象存储
CREATE OR REPLACE VIEW governance.v_storage_policy AS
SELECT
    policy_name,
    volume_name,
    volume_priority,
    disks,
    max_data_part_size_bytes,
    move_factor
FROM system.storage_policies;

-- 各表声明的 TTL：用于比对"实际配置 vs 生命周期策略"
CREATE OR REPLACE VIEW governance.v_table_ttl AS
SELECT
    database,
    name                AS table,
    engine,
    partition_key,
    sorting_key,
    primary_key,
    total_rows,
    total_bytes
FROM system.tables
WHERE database IN ('ods', 'dwd', 'dws', 'ads', 'rt', 'governance');

-- part 在各磁盘上的分布：观察冷数据是否已下沉到对象存储
CREATE OR REPLACE VIEW governance.v_part_disk_usage AS
SELECT
    database,
    table,
    disk_name,
    count()                         AS parts,
    sum(rows)                       AS rows,
    formatReadableSize(sum(bytes_on_disk)) AS size
FROM system.parts
WHERE active
GROUP BY database, table, disk_name
ORDER BY database, table, disk_name;

-- 分区级别明细：生命周期治理按分区操作时，用于挑选"最老的一批分区"
CREATE OR REPLACE VIEW governance.v_partition_age AS
SELECT
    database,
    table,
    partition,
    min(min_date)                   AS min_date,
    max(max_date)                   AS max_date,
    count()                         AS parts,
    formatReadableSize(sum(bytes_on_disk)) AS size
FROM system.parts
WHERE active
GROUP BY database, table, partition
ORDER BY database, table, partition;
