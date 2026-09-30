# 08 · 运维手册

## 8.1 日常作业

| 时间（UTC） | 作业 | 触发方式 | 说明 |
|---|---|---|---|
| 02:10 | 离线主链路 | Airflow `tmdb_offline_daily` | 采集 → 分层 → 同步 → 质量门禁 → 图装载 → 血缘落库 |
| 03:30（周日） | 全量采集 | Airflow `tmdb_full_load_weekly` | 断点续传；完成后重跑离线与质量检查 |
| 05:00 | 治理日常 | Airflow `tmdb_governance_daily` | 指标校验、血缘落库、生命周期 dry-run、质量检查 |
| 每 15 分钟 | 实时守护 | Airflow `tmdb_realtime_monitor` | 检查实时数据新鲜度，滞后则告警 |
| 常驻 | Flink 作业 | Compose / K8s | entity-change、popularity-trend |

## 8.2 监控告警

**关键指标与阈值**

| 指标 | 告警阈值 | 含义 |
|---|---|---|
| `up{job="clickhouse"} == 0` | 持续 2 分钟 | ClickHouse 不可用（P0） |
| `flink_jobmanager_numRunningJobs == 0` | 持续 5 分钟 | 实时作业都没在跑 |
| 实时数据滞后 | > 15 分钟 | `rt` 表最新事件时间落后 |
| `clickhouse_flush_failures` | 非 0 | Sink 写入失败 |
| `realtime_invalid_events` 突增 | 环比 3 倍 | 上游结构变更或序列化问题 |
| ClickHouse 磁盘使用率 | > 80% | 需要扩容或加速冷下沉 |
| Kafka 消费积压 | > 10 万条 | 实时处理跟不上，需扩容或排查反压 |

**告警规则位置**：`deploy/compose/conf/prometheus/rules/tmdbwh-alerts.yml`，
K8s 环境复用同一份（Helm ConfigMap），保证两套环境口径一致。

**仪表盘**：`deploy/compose/conf/grafana/dashboards/platform-overview.json`
（平台总览：各组件存活、实时延迟、写入量、质量结果）。

## 8.3 常见运维操作

**补跑某一天**

```bash
java -jar offline.jar run --date 2026-09-25 --sync
```

只覆盖目标分区，其他日期不受影响。质量门禁会照常执行。

**重跑实时作业（从 savepoint）**

```bash
flink savepoint <jobId> s3a://tmdb-lake/flink/savepoints
flink run -s <savepoint-path> realtime.jar run --job popularity-trend
```

**重建图**

```bash
java -jar graph.jar load --clear --yes   # 清空后重建（需确认）
java -jar graph.jar load                 # 幂等增量装载（推荐）
```

**清理过期数据**

```bash
java -jar governance.jar lifecycle                 # 看计划
java -jar governance.jar lifecycle --apply         # 执行（不可逆，务必先确认计划）
```

**Iceberg 治理**

```bash
java -jar offline.jar iceberg --table dwd.fact_movie_credit --orphan --dry-run
java -jar offline.jar iceberg --table dwd.fact_movie_credit --orphan --expire
java -jar offline.jar iceberg --table dwd.fact_movie_credit --compact
```

## 8.4 备份与恢复

| 数据 | 备份方式 | 恢复优先级 | 说明 |
|---|---|---|---|
| ClickHouse 业务数据 | 快照 / `BACKUP` 到对象存储 | 中 | 可从湖仓重灌，但耗时 |
| 对象存储（湖仓 + raw） | 桶版本控制 + 跨区域复制 | **高** | 原始报文丢了无法重放 |
| Iceberg 元数据 | 随对象存储一起备份 | **高** | 快照丢失等于表丢失 |
| Neo4j | `neo4j-admin dump` | 低 | 可从数仓重建 |
| Postgres（Metastore） | pg_dump | **高** | Metastore 丢失则 Iceberg 表不可访问 |
| 治理元数据 | 随 ClickHouse 备份 | 中 | 血缘 / 质量历史 |

**恢复演练**：至少每季度演练一次"从对象存储 + Metastore 备份恢复 Iceberg 表"，
否则备份是否有效无从得知。

## 8.5 扩容

| 瓶颈 | 现象 | 扩容方式 |
|---|---|---|
| ClickHouse 查询慢 | 查询耗时上升、CPU 打满 | 加分片（需重新平衡数据）或升配 |
| ClickHouse 存储不足 | 磁盘 > 80% | 缩短 TTL、加快冷下沉、加磁盘 |
| Spark 作业慢 | 离线链路超时 | 加 Worker、调 `spark.sql.shuffle.partitions` |
| Flink 反压 | 消费积压、checkpoint 超时 | 加 TaskManager、提高并行度（需 savepoint 恢复） |
| Kafka 积压 | lag 持续增长 | 加分区（会影响 key 的分区映射，需评估） |

## 8.6 值班手册

**第一步：判断影响面**

1. 数据还新吗？（查 `rt` 表最新时间、`dws` 表最新 `dt`）
2. 影响哪些下游？（`governance lineage --table <表>` 看下游）
3. 是采集、计算还是存储的问题？（看各组件存活与作业状态）

**第二步：止损**

- 实时滞后但离线正常 → 先不动（离线会兜底），排查 Flink；
- 离线未产出 → 检查采集（是否限流/上游故障）与 Spark 作业；
- ClickHouse 不可用 → 切查询到湖仓（Spark SQL），恢复后重灌。

**第三步：恢复与复盘**

- 补跑缺失日期（离线链路幂等，可直接重跑）；
- 记录到 `governance.lifecycle_execution` 之外的事件记录（建议接入工单系统）；
- 复盘要点：为什么没提前发现（监控缺口）、如何更快恢复（脚本/文档缺口）。
