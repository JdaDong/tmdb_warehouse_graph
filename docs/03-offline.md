# 03 · 离线数仓（Spark）

## 3.1 作业入口

```bash
# 全链路（ODS → DWD → DWS → ADS）
java -jar offline.jar run --date 2026-09-30

# 并同步 ClickHouse
java -jar offline.jar run --date 2026-09-30 --sync

# Iceberg 维护（默认只打印，加 --apply 才执行）
java -jar offline.jar iceberg --table dwd.fact_movie_credit --orphan --expire --dry-run
java -jar offline.jar iceberg --table dwd.fact_movie_credit --compact

# 生成日期维（一次性）
java -jar offline.jar date-dim --start-year 2010 --years 20

# 本地调试（用 local[*] 启动 Spark）
java -jar offline.jar run --date 2026-09-30 --local
```

## 3.2 链路拆解

```
OdsReader       读取 raw/{entity}/dt=yyyy-MM-dd/*.ndjson.gz（缺失时返回空 Dataset）
   ↓
DwdTransform    from_json 解析 → 清洗 → 维度建模 / 事实 / 桥接
   ↓
Scd2            维度版本合并（内容变化才产生新版本）
   ↓
DwsTransform    主题域聚合（含 7 日环比、ROI）
   ↓
AdsTransform    排名与结果表
   ↓
LakeTables      写入 Iceberg（维度全量覆盖；其余按 dt 分区覆盖）
   ↓
OfflineSqlRunner → ClickHouse 同步（维度按窗口删除重写；事实按分区替换）
```

## 3.3 幂等性是怎么保证的

| 写入对象 | 方式 | 为什么幂等 |
|---|---|---|
| Iceberg 维度表 | `SaveMode.Overwrite` 全量重写 | 版本链整体重写，重跑结果完全一致 |
| Iceberg 事实 / 汇总表 | 按 `dt` 动态分区覆盖 | 只替换目标分区，重复执行不翻倍 |
| ClickHouse 维度 | 删除 `valid_from` 落在业务日期的行 → 重写 | 删除范围与写入范围一致 |
| ClickHouse 事实 / 汇总 | 暂存表 + 分区替换（MOVE / ATTACH） | 分区级原子切换，不存在"已删未插"的中间态 |

`load_time` 固定为**业务日期零点**（`DwdTransform.loadTimeOf`），不是 `now()`。
这是幂等的关键：如果用当前时间，每次重跑都会产生新版本，SCD2 版本链会无限膨胀。

## 3.4 分区替换的实现与降级

`OfflineSqlRunner.replacePartition` 生成的语句序列：

```sql
DROP TABLE IF EXISTS dws.dws_movie_metric_1d_stg_<runId> [ON CLUSTER ...];
CREATE TABLE IF NOT EXISTS dws.dws_movie_metric_1d_stg_<runId> AS dws.dws_movie_metric_1d [ON CLUSTER ...];
ALTER TABLE dws.dws_movie_metric_1d MOVE PARTITION ID '202609' TO TABLE dws.dws_movie_metric_1d_stg_<runId>;
INSERT INTO dws.dws_movie_metric_1d_stg_<runId> SELECT * FROM s3('<lake>/dws.dws_movie_metric_1d/dt=2026-09-30/*.parquet', ...);
ALTER TABLE dws.dws_movie_metric_1d DROP PARTITION ID '202609';
ALTER TABLE dws.dws_movie_metric_1d ATTACH PARTITION ID '202609' FROM dws.dws_movie_metric_1d_stg_<runId>;
DROP TABLE IF EXISTS dws.dws_movie_metric_1d_stg_<runId>;
```

**版本兼容性（重要）**：

- `MOVE PARTITION TO TABLE` 仅移动元数据，最快；要求暂存表与目标表都不是 Replicated 引擎；
- 若为 Replicated 表或该语法不被支持，降级为 `REPLACE PARTITION FROM`（会复制数据，但可用）；
- 两者都没有 `IF EXISTS` 形式，执行失败时由执行器记录并降级（见 `OfflineSqlRunner` 注释）。

**分区 ID** 与迁移脚本的 `PARTITION BY toYYYYMM(dt)` 对应（`OfflineSqlRunner.partitionId`）。
若将来改为按天分区，必须同步修改该方法——集中在一处就是为了让这种联动不会被遗漏。

## 3.5 ClickHouse 如何读取湖仓结果

Spark 把结果写成 Parquet（按 `dt` 分区目录），ClickHouse 通过 `s3()` 表函数读取：

```sql
INSERT INTO dws.dws_movie_metric_1d
SELECT ... FROM s3('<lake>/dws.dws_movie_metric_1d/dt=2026-09-30/*.parquet', '<ak>', '<sk>', 'Parquet');
```

注意：Spark 的分区列**不写进 Parquet 文件内部**，因此 `dt` 由路径中的 `dt=` 目录保证。
湖仓地址通过 `S3_LAKE_URL` 配置（必须用容器内可达的 MinIO 地址）。

若目标环境不允许 ClickHouse 访问对象存储，可改为由 Spark 通过 JDBC 直接写入
（需放宽 `LakeTables` 的实现，代价是失去分区级原子切换）。

## 3.6 小文件与快照治理

Iceberg 每次写入都会产生新快照与数据文件，长期不治理会出现三类问题：

| 问题 | 现象 | 处置 |
|---|---|---|
| 快照过多 | 查询规划变慢、元数据膨胀 | `expire_snapshots`（保留最近 3 个 + N 天内） |
| 孤儿文件 | 对象存储成本持续上升 | `remove_orphan_files`（先 dry-run 看清单） |
| 小文件过多 | Spark 读取 task 数膨胀 | `rewrite_data_files`（binpack 到 128MB） |

```bash
java -jar offline.jar iceberg --table dwd.fact_movie_credit --orphan --dry-run   # 看清单
java -jar offline.jar iceberg --table dwd.fact_movie_credit --orphan             # 真删
java -jar offline.jar iceberg --table dwd.fact_movie_credit --compact            # 合并小文件
```

注意：`--compact` 是写操作，dry-run 时会被跳过（不会"说好不写却写了"）。

## 3.7 常见运维操作

**补跑某一天**：

```bash
java -jar offline.jar run --date 2026-09-25 --sync
```

只会覆盖 `dt=2026-09-25` 的分区，其他日期不受影响。

**重建维度**（口径调整后）：

```bash
# 维度是全量覆盖：直接重跑即可，会从湖仓现有版本链重新计算
java -jar offline.jar run --date 2026-09-30 --sync
```

**回滚**（口径改错时）：Iceberg 支持快照回滚：

```sql
CALL lake.system.rollback_to_snapshot(table => 'dwd.fact_movie_credit', snapshot_id => <id>);
-- 查看快照
SELECT * FROM lake.dwd.fact_movie_credit.snapshots;
```

## 3.8 性能基线（每日百万级增量）

| 阶段 | 参考耗时（2 Worker × 2C/4G） | 调优方向 |
|---|---|---|
| ODS 读取 + 解析 | 3–8 min | `spark.sql.files.maxPartitionBytes`、避免 schema 推断 |
| DWD 建模 | 5–15 min | 广播小维度、控制 shuffle 分区数（默认 8） |
| DWS / ADS 聚合 | 3–10 min | 预聚合、避免 count(distinct) 滥用 |
| 湖仓写入 | 5–15 min | 输出文件数（目标 128MB/文件） |
| ClickHouse 同步 | 1–5 min | 批大小、分区替换而不是逐行插入 |
