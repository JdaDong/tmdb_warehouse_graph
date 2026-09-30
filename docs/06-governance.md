# 06 · 数据治理（围绕 OLAP 引擎）

治理对象就是 ClickHouse 里的表与字段。治理元数据自身也放在 `governance` 库，
与业务数据同库管理、同样的 TTL 与冷热策略——避免"治理元数据自己失控"。

## 6.1 迁移（Schema Migration）

```bash
java -jar governance.jar migrate            # 应用待执行迁移
java -jar governance.jar migrate --dry-run  # 只打印计划
java -jar governance.jar migrate-info       # 当前版本与待执行脚本
java -jar governance.jar migrate-history    # 已执行记录
```

- 脚本位于 `tmdb-governance/src/main/resources/clickhouse/migrations/V*_*.sql`；
- 每个脚本记录校验和，**已执行脚本被修改会报错**（防止"改历史"导致环境不一致）；
- 集群模式下 DDL 自动追加 `ON CLUSTER`；
- 迁移记录表本身也是 ReplacingMergeTree，重复执行安全。

分层脚本：V1 库、V2 贴源实体、V3 事件、V4 维度（SCD2）、V5 桥接、V6 事实、
V7 DWS、V8 ADS、V9 RT、V10 治理表、V11 物化视图、V12 生命周期视图。

## 6.2 数据质量

```bash
java -jar governance.jar quality --date 2026-09-30
```

**规则类型**（配置在 `tmdbwh.governance.quality.rules`）：

| 类型 | 指标 | 判定 |
|---|---|---|
| `ROW_COUNT` | 行数 | `>= min`（为空通常意味着采集挂了） |
| `UNIQUE` | 组合键重复数 | `<= max-duplicates` |
| `NOT_NULL` | 空值比例 | `<= max-null-ratio` |
| `RANGE` | 越界行数 | `<= 0` |
| `FRESHNESS` | 数据滞后天数 | `<= max-lag-days` |
| `REFERENTIAL` | 孤儿记录数 | `<= max-orphans` |

**设计要点**：

- 统一为"计算一个指标值再与阈值比较"，新增规则只需加一个类型分支；
- **只检查当天分区**（SQL 带 `WHERE dt = ...`）：全表 count 在亿级表上会拖慢集群，
  且"历史某天有问题"与"今天任务失败"是两件事；
- **单条规则查询失败记为一条失败结果并继续**，不能让一次抖动停摆整轮检查；
- 严重级别：`BLOCKER`（阻断下游）/ `WARN`（记录告警）/ `INFO`（观察趋势）；
- 结果写入 `governance.dq_result`，可回答"这个问题持续多久了"；
- `fail-on-blocker` 可关闭：上线初期先只观察，稳定后再打开。

**退出码**：BLOCKER 失败时进程以非 0 退出，调度系统据此决定是否继续下游
（`dag_tmdb_offline_daily` 的质量门禁就是这么实现的）。

## 6.3 血缘

```bash
java -jar governance.jar lineage                                  # 列出全部血缘边
java -jar governance.jar lineage --table dwd.dim_movie            # 下游（影响分析）
java -jar governance.jar lineage --table ads.ads_top_movie --direction upstream  # 上游（来源追溯）
java -jar governance.jar lineage --persist                        # 写入 governance.lineage_edge
```

**为什么用声明式血缘**：离线作业大量使用 Spark DataFrame API（不是纯 SQL），
SQL 解析器解析不到；实时作业同理。声明式的代价是需要人工维护，换来的是准确且可解释。
常见做法是两者结合：核心链路声明式，长尾查询用 `system.query_log` 补全。

支持递归查询（含间接上下游），且**带 visited 集合**——
血缘里出现环（回流补数）时不会死循环。

## 6.4 生命周期

```bash
java -jar governance.jar lifecycle           # 默认 dry-run：只打印计划
java -jar governance.jar lifecycle --apply   # 真正执行
```

策略三段式（配置在 `tmdbwh.governance.lifecycle.policies`）：

| 参数 | 含义 |
|---|---|
| `cold-after-days` | 多久后迁到冷存储（对象存储），成本约为本地 SSD 的 1/5，查询仍可用 |
| `ttl-days` | 多久后彻底删除（合规 + 成本控制）；0 表示不自动删除 |
| `partition-days` | 按分区清理的兜底（TTL 是异步的，长期不触发时需主动 DROP PARTITION） |

**默认 dry-run**：这些 DDL 会真的删数据，自动任务只生成计划并写治理表，
由运维确认后再手动执行。历史上"自动清理误删"的事故基本都源于把不可逆操作做成了定时任务。

执行记录写入 `governance.lifecycle_execution`（计划 vs 实际），
保留"数据是什么时候按什么策略被删的"，合规场景下这是必须的。

## 6.5 权限（RBAC）

```bash
java -jar governance.jar access              # 预览授权语句
java -jar governance.jar access --apply      # 执行
java -jar governance.jar access --apply --user alice --role analyst
```

| 角色 | 授权范围 | 说明 |
|---|---|---|
| `analyst` | `dws.*` / `ads.*` / `rt.*` 只读 | 禁止直接查贴源，避免误用未清洗数据 |
| `engineer` | 各层 SELECT + INSERT，`governance.*` INSERT | 开发与服务账号 |
| `admin` | `*.*` ALL | 管理员 |

**为什么用角色而不是直接授权**：岗位是稳定的，人员是流动的。
按角色授权后人员变动只需改用户角色，不需要重新梳理每张表的权限，
也避免"离职同事还留着一堆表权限"。

## 6.6 指标口径

```bash
java -jar governance.jar metrics               # 列出定义
java -jar governance.jar metrics --validate    # 校验表 / 字段是否存在
```

定义字段：名称、出自的表、计算表达式、负责人、说明（配置在 `tmdbwh.governance.metrics.definitions`）。

校验三件事，都是"口径失控"的典型起因：

1. **重名不同义**：同一指标名两种算法——**在加载阶段直接失败**，
   这是最容易导致报表互相打架的情况；
2. **表不存在**：指标引用的表被改名或删除；
3. **字段不存在**：表达式里的列在表里没有（表结构变更后常见，且不会报错、只会静默出错）。

## 6.7 元数据同步（OpenMetadata）

```bash
make om-ingest      # 需要 OM_JWT_TOKEN
```

采集 ClickHouse 的表 / 字段 / 使用信息到 OpenMetadata，与项目内的血缘、质量结果互补：
OpenMetadata 提供全局搜索与业务术语，本项目提供"作业级别的准确血缘"。

## 6.8 治理看板

| 表 | 用途 |
|---|---|
| `governance.metadata_snapshot` | 表级元数据快照（结构变更追踪） |
| `governance.metadata_column_snapshot` | 字段级快照 |
| `governance.standard_check_result` | 命名规范 / 注释完备度 / 必备字段检查 |
| `governance.dq_result` | 质量检查结果（趋势观察） |
| `governance.lineage_edge` | 血缘边 |
| `governance.lifecycle_execution` | 生命周期执行记录 |
| `governance.query_cost_report` | 慢查询与成本分析（来自 system.query_log） |
