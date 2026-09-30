# 04 · 实时数仓（Flink）

## 4.1 作业清单

| 作业 | 输入 Topic | 输出 | 说明 |
|---|---|---|---|
| `entity-change` | `tmdb.entity.change` | `ods.ods_change_event` + DLQ | 解码 → 内容级去重 → 落 ODS |
| `popularity-trend` | `tmdb.popularity` | `ods.ods_popularity_event`、`rt.rt_movie_popularity`、`rt.rt_surge_alert` + DLQ | 窗口聚合 + 飙升检测 |

```bash
# 查看装配信息（不启动，发布前检查用）
java -jar realtime.jar plan --job popularity-trend

# 提交作业
java -jar realtime.jar run --job entity-change
java -jar realtime.jar run --job popularity-trend --checkpoint-dir s3a://tmdb-lake/flink/ck

# 指定状态后端（RocksDB，大状态场景）
java -jar realtime.jar run --job popularity-trend \
     --state-backend org.apache.flink.state.rocksdb.EmbeddedRocksDBStateBackend
```

## 4.2 语义说明（务必理解）

**Flink 侧是 Exactly-Once，Sink 侧是 At-Least-Once + 下游幂等。**

- Flink 通过 checkpoint + Kafka 位移由 checkpoint 提交，保证"处理且仅处理一次"；
- ClickHouse 不支持分布式事务（无 XA），无法把事务延伸到 Sink；
- 因此所有目标表都用 `ReplacingMergeTree`（ORDER BY 业务主键、version 取事件时间），
  重复写入在后台合并时自动去重，**最终结果与"只写一次"一致**。

这不是妥协，而是 ClickHouse 生态的标准做法：幂等性由存储引擎保证，而不是靠 Sink 事务。

## 4.3 去重：为什么按内容哈希而不是 eventId

上游 `changes` 接口在"轮询窗口重叠"或"作业重放"时会重复投递同一实体，
而且事件 ID 可能不同（例如 adult 字段翻转产生两条内容相同的事件）。
只按 `eventId` 去重无法覆盖"ID 不同但内容相同"，因此 `EventDeduplicator` 按 `contentHash` 比较。

**状态必须设 TTL**（默认 7 天，见 `tmdbwh.realtime.dedup.state-ttl`）：
状态大小与实体数成正比，不设 TTL 会让 RocksDB 状态无限增长、checkpoint 越来越慢。
TTL 之后即使内容相同也会重新下发，这让下游能周期性看到"心跳"，便于判断链路是否还活着。

## 4.4 时间语义与窗口

```java
WatermarkStrategy
  .<EventEnvelope>forBoundedOutOfOrderness(allowedLateness)  // 默认 1 分钟
  .withTimestampAssigner((e, ts) -> e.getEventTime())
  .withIdleness(idleness);                                   // 默认 2 分钟
```

- **事件时间**为准（不是处理时间）：重放历史数据时结果才一致；
- **空闲分区判定**必须设置：某些 movie_id 长时间无数据时，若不标记空闲，
  watermark 不会推进，窗口永不触发（表现为"部分数据一直不出结果"）；
- 窗口为滚动事件时间窗口（默认 5 分钟）。

`lag(7)` 用于 7 日环比，它按**行偏移**，因此要求输入是"每实体每日一条"的连续快照。
离线侧由 `dwd.fact_movie_daily_snapshot` 的唯一性约束保证，实时侧不计算日环比。

## 4.5 飙升检测

三重条件，缺一不可（这是最容易写错的地方）：

1. **涨幅**：`当前窗口均值 / 上一窗口均值 >= 阈值`（默认 1.5）；
2. **样本量**：窗口内样本数 >= `min-samples`（默认 3）——样本太少时均值不可信；
3. **基线下限**：当前热度 >= `min-baseline`（默认 20）——冷门内容从 0.5 涨到 5 是 10 倍但没有业务价值。

告警 ID = `实体类型:实体ID:窗口起始`，重放 / 重算时 ID 稳定，
配合 `ReplacingMergeTree(alert_time)` 不会产生重复告警。

`growthRatio` 在基线缺失时返回 0（不是 Infinity）——Infinity 会污染下游 `avg`/`sum` 聚合。

## 4.6 坏消息处理（DLQ）

解码失败、结构不完整、未知枚举值的消息**不抛异常**，而是旁路到 DLQ Topic：
单条脏数据若让作业抛异常，会进入"失败→重启→读到同一条→再失败"的循环，整条链路被卡死。

DLQ 消息保留原始字节与错误信息，便于修复后重放。
建议定期监控 DLQ 数量（`realtime_invalid_events` 指标），突增说明上游结构变更或序列化有问题。

## 4.7 关键配置

| 配置 | 默认 | 说明 |
|---|---|---|
| `checkpoint.interval` | 30s | 影响端到端延迟与恢复粒度 |
| `checkpoint.mode` | exactly_once | 可切 at_least_once 降低开销 |
| `checkpoint.externalized` | true | 取消作业后保留 checkpoint，升级可续跑 |
| `dedup.state-ttl` | 7d | 状态过期时间，防止状态无限增长 |
| `trend.window-size` | 5min | 窗口长度 |
| `trend.allowed-lateness` | 1min | 乱序容忍 |
| `trend.idleness` | 2min | 空闲分区判定 |
| `surge.ratio-threshold` | 1.5 | 飙升涨幅阈值 |
| `surge.min-samples` | 3 | 最少样本数 |
| `surge.min-baseline` | 20.0 | 基线下限 |
| `sink.batch-size` | 1000 | ClickHouse 批量写入大小 |
| `parallelism` | 2 | 不能超过 Flink 总槽数 |

## 4.8 监控指标

| 指标 | 含义 | 告警建议 |
|---|---|---|
| `realtime_end_to_end_latency_ms` | 事件时间 → 入库时间 | > 5 min 告警 |
| `realtime_duplicate_events` | 被去重丢弃的事件数 | 突增说明上游重复投递或发生重放 |
| `realtime_invalid_events` | 坏消息数 | 突增说明上游结构变更 |
| `clickhouse_written_rows` | Sink 写入行数 | 长时间为 0 说明链路停滞 |
| `clickhouse_flush_failures` | Sink 写入失败次数 | 非 0 即需排查 |

**"作业在跑"不等于"数据是最新的"**：Kafka 积压、位移卡住、Sink 失败都会让进程活着但数据停滞。
因此 `dag_tmdb_realtime_monitor` 直接查 `rt.rt_movie_popularity` 的最新事件时间判断新鲜度。

## 4.9 运维操作

**从 savepoint 恢复 / 升级**：

```bash
flink savepoint <jobId> s3a://tmdb-lake/flink/savepoints
flink run -s s3a://tmdb-lake/flink/savepoints/savepoint-xxx realtime.jar run --job popularity-trend
```

**调整并行度**：需先 savepoint 再恢复（有状态作业不能直接改并行度重启）。

**重放某段时间**：重置 Kafka 位移到指定时间点，作业会从该位置重新处理；
由于 Sink 幂等，重复处理不会产生重复数据（但会触发重复告警去重逻辑，属正常）。
