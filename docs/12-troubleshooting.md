# 12 · 故障排查手册

## 12.1 通用排查顺序

1. **数据还新吗**：`SELECT max(dt) FROM dws.dws_movie_metric_1d`、`SELECT max(event_time) FROM rt.rt_movie_popularity`
2. **影响面**：`governance lineage --table <表>` 看下游
3. **哪一环**：采集（对象存储有文件吗）→ 计算（Spark/Flink 作业状态）→ 存储（ClickHouse 存活）

---

## 12.2 采集

### 现象：raw 分区没有文件

- 检查 `S3_ENDPOINT` / `S3_BUCKET` 与凭据；
- 检查采集作业日志（`make logs SVC=ingestion`）；
- Mock 模式下确认 `mock` profile 已启动，且 `TMDB_BASE_URL` 指向 WireMock。

### 现象：大量 429（限流）

- 降低 `tmdb.rate-limit-per-second` 或并发；
- 采集已内置退避重试并遵守 `Retry-After`；若持续 429 说明配额不足，需申请或降频。

### 现象：采集作业卡在某个 ID 不动

- 全量采集支持断点续传（状态在 `_state/`），重启即可从断点继续；
- 确认对象存储的 `_state/` 未被误删。

---

## 12.3 离线（Spark）

### 现象：作业报 `NoSuchBucket` / `Access Denied`

- 检查 `spark.hadoop.fs.s3a.endpoint`、`access.key`、`secret.key`；
- MinIO 需 `path.style.access = true`；
- 容器内访问 MinIO 要用容器网络地址（不是 localhost）。

### 现象：`MISSING_ATTRIBUTES ... Attribute(s) with the same name`

- 典型原因：join 后两侧存在同名列，随后 `withColumn` 替换该列导致引用失效；
- 处置：先写成新列名 → `drop` 旧列 → `rename`，或按**列名**重新 `select`（不要用旧 Dataset 的 Column 实例）。

### 现象：重跑后数据翻倍

- 说明写入不是"覆盖"而是"追加"；
- 检查：`LakeTables.writePartition`（分区覆盖）是否被误用为 `writeAppend`；
  维度表应走全量覆盖。

### 现象：维度版本链异常（出现重叠或空洞）

- SCD2 收敛逻辑依赖 `valid_to = 2099-12-31` 作为"当前有效"哨兵值；
- 若手工改过数据导致哨兵值被破坏，需重建维度（从湖仓重跑）。

### 现象：Iceberg 表查询变慢

- 快照过多：`iceberg --table <表> --expire`；
- 小文件过多：`iceberg --table <表> --compact`；
- 孤儿文件：`iceberg --table <表> --orphan`（先 dry-run）。

---

## 12.4 实时（Flink）

### 现象：窗口一直没有输出

- **最常见原因**：未设置空闲分区判定，某些 key 长时间无数据导致 watermark 不推进；
  检查 `tmdbwh.realtime.trend.idleness`；
- 检查事件时间字段是否正确（用处理时间会导致窗口行为异常）。

### 现象：作业反复重启

- 坏消息引起：检查 DLQ 是否有大量消息（应旁路到 DLQ 而不是抛异常）；
- Sink 写入失败：检查 ClickHouse 连通性与 `clickhouse_flush_failures`。

### 现象：数据重复

- ClickHouse 目标表是否用了 `ReplacingMergeTree`（ORDER BY 业务主键 + version）；
- 若表引擎是 `MergeTree`，重复写入不会被去重，需要改表。

### 现象：延迟持续升高

- 看 Kafka 消费积压：积压增长说明处理跟不上，需扩容或提高并行度；
- 看 checkpoint 耗时：状态过大时应启用 RocksDB 增量 checkpoint。

---

## 12.5 ClickHouse

### 现象：查询突然变慢

- 看 `system.query_log` 找耗时最长的查询（`governance.query_cost_report`）；
- 检查是否有未带分区条件的全表扫描（应始终带 `dt`）；
- 检查后台 merge 是否堆积（`BackgroundPoolTask`）。

### 现象：磁盘使用率过高

- 缩短 TTL / 加快冷下沉（`governance lifecycle`）；
- 检查是否有表的分区未按预期清理。

### 现象：分区替换失败（MOVE PARTITION 报错）

- 目标表是 Replicated 引擎时 `MOVE PARTITION TO TABLE` 不可用，降级为 `REPLACE PARTITION FROM`；
- 两者都没有 `IF EXISTS`，失败时需人工确认状态后再重试（执行器会记录到治理表）。

---

## 12.6 图（Neo4j）

| 现象 | 原因 | 处置 |
|---|---|---|
| 关系为 0 但节点都在 | 装载顺序错误，或 from_id/to_id 类型不一致 | 检查装载顺序与字段类型 |
| 重复节点 | 约束未建立 | `graph.jar init` 重建约束 |
| 装载很慢 | 逐条提交或未走 UNWIND | 检查批大小（默认 1000） |
| 相似推荐不相关 | 关系装载不完整 | `graph.jar stats` 检查各关系数量 |

---

## 12.7 治理

### 现象：质量检查大量 BLOCKER 失败

- 先看是"数据真的有问题"还是"规则配置错了"（例如阈值不适应当天数据量）；
- 上线初期可用 `fail-on-blocker=false` 只观察，稳定后再打开。

### 现象：指标校验报"字段不存在"

- 表结构变更后指标定义未同步；更新 `tmdbwh.governance.metrics.definitions`。

### 现象：迁移报校验和不匹配

- 已执行的迁移脚本被修改过。这是保护措施：
  正确做法是新增一个迁移脚本修正，而不是改历史脚本。

---

## 12.8 环境

### 现象：`docker compose up` 后服务反复重启

- 内存不足（全部组件建议 ≥ 16GB）：改用最小 profile `make up-minimal`；
- Apple Silicon 上 `apache/hive:3.1.3` 只有 amd64 镜像，会以模拟方式运行（较慢），属正常。

### 现象：Maven 依赖下载失败

- 无法直连 Maven Central 时用镜像：`make build MVN_MIRROR=cn`。

### 现象：单元测试报"找不到 Docker"

- 集成测试（`-Pit`）需要 Docker，无 Docker 时自动跳过；
  若报的是错误而不是跳过，检查 Docker 守护进程状态。
