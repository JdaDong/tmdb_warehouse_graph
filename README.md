# TMDB Warehouse & Graph Platform

以 [TMDB](https://www.themoviedb.org/) 开放 API 为数据源的生产级数据平台：

- **离线数仓**：Spark 3.5 + Iceberg 1.5（ODS → DWD → DWS → ADS，维度建模 / SCD2）
- **实时数仓**：Kafka 3.7 + Flink 1.18（去重、窗口聚合、热度飙升检测、死信队列）
- **OLAP 与数据治理**：ClickHouse 24.3（元数据、标准、质量门禁、指标口径、血缘、生命周期、权限、成本）
- **知识图谱**：Neo4j 5（影视关系网络、GDS 图算法、结果回流）
- **部署**：Docker Compose（本地一键启动）与 Kubernetes Helm

> 六个业务模块的主体代码、单元测试、运维脚本与设计文档均已提供。
> 首次使用请先 `make build` 构建，再用 `make smoke` 端到端验证环境（无 TMDB API Key 也可用内置 Mock 跑通）。

## 模块

| 模块 | 说明 |
| --- | --- |
| `tmdb-common` | 类型化配置、TMDB 领域模型、事件信封、JSON、MinIO/S3、ClickHouse 客户端、重试/脱敏/时间工具 |
| `tmdb-ingestion` | TMDB 采集：全量 / 增量 changes / 热度轮询，落湖与 Kafka 投递 |
| `tmdb-offline` | Spark 离线分层计算、ClickHouse 分区替换同步、Iceberg 维护 |
| `tmdb-realtime` | Flink 实时作业与 ClickHouse / Neo4j / Iceberg Sink |
| `tmdb-graph` | Neo4j 约束初始化、GDS 分析、Cypher 用例、结果回流 |
| `tmdb-governance` | 以 ClickHouse 为中心的 OLAP 数据治理引擎 |

## 构建

环境要求：JDK 11~17（Spark 3.5 / Flink 1.18 的官方支持范围）、Maven 3.8+。macOS 下 `make` 会自动选用 JDK 17。

```bash
make build                    # 编译 + Checkstyle + 单元测试 + 覆盖率门禁 + 打包
make build MVN_MIRROR=cn      # 无法直连 Maven Central 时使用阿里云镜像
make test MODULE=tmdb-common  # 只测试单个模块（自动带上依赖模块）
make it                       # 追加 Testcontainers 集成测试（需要 Docker，无 Docker 时自动跳过）
make coverage                 # 打印各模块行覆盖率
make fmt                      # 自动格式化
```

## 配置

所有配置集中在 `tmdb-common/src/main/resources/reference.conf`（HOCON），支持三级覆盖：

1. JVM 系统属性：`-Dtmdbwh.tmdb.rate-limit-per-second=20`
2. 配置文件：`-Dconfig.file=config/application.conf`
3. 环境变量（密钥类配置只能用这种方式注入）：

| 环境变量 | 说明 |
| --- | --- |
| `TMDB_BEARER_TOKEN` / `TMDB_API_KEY` | TMDB 凭证（二选一，推荐 v4 Bearer Token） |
| `TMDB_MOCK_MODE` | `true` 时使用 WireMock 模拟 TMDB，无需凭证 |
| `S3_ENDPOINT` / `S3_ACCESS_KEY` / `S3_SECRET_KEY` / `S3_BUCKET` | MinIO / S3 |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka |
| `CLICKHOUSE_URL` / `CLICKHOUSE_USER` / `CLICKHOUSE_PASSWORD` / `CLICKHOUSE_CLUSTER` | ClickHouse |
| `NEO4J_URI` / `NEO4J_USER` / `NEO4J_PASSWORD` | Neo4j |
| `HIVE_METASTORE_URI` / `ICEBERG_WAREHOUSE` | Iceberg Catalog |

配置在启动时一次性汇总并报告所有校验错误；打印配置时密钥会自动脱敏。

## 数据库迁移（ClickHouse）

```bash
make dist                                   # 先构建 fat-jar
make cli CLI_ARGS='governance migrate'      # 应用待执行的迁移
make cli CLI_ARGS='governance migrate --dry-run'
make cli CLI_ARGS='governance migrate --info'
```

约定：

- 脚本位于 `tmdb-governance/src/main/resources/clickhouse/migrations/V<n>__<name>.sql`，**只增不改**；
- 执行记录写入 `governance.schema_migrations`（版本、名称、校验和、耗时、执行人）；
- 已执行脚本被修改会因**校验和不匹配**直接报错；脚本最高版本低于库内版本会报**版本回退**；
- 集群模式下由迁移器自动追加 `ON CLUSTER`，脚本中不写死；迁移前会校验集群名是否存在于 `system.clusters`；
- 禁止手工 DDL。

当前版本 V12，覆盖：分层数据库（V1）、ODS 原始报文与事件（V2-V3）、DWD 维度/桥接/事实（V4-V6，含 SCD2）、
DWS 汇总（V7）、ADS 报表（V8）、实时层（V9）、治理结果表（V10）、物化视图（V11）、生命周期视图（V12）。

> 部署前提：所有带冷热分层的表都声明了 `storage_policy = 'hot_cold'`，
> 该策略由 `deploy/compose/conf/clickhouse/config.d/20-storage.xml` 提供；换成其他环境时需先创建同名策略。

## 采集使用

```bash
# 构建 fat-jar 到 dist/jars
make dist

# 全量采集（抽样 200 条，走 TMDB Mock）
java -Dconfig.file=config/application.conf -jar dist/jars/tmdb-ingestion-*-all.jar \
     full --entity movie --max-ids 200

# 增量变更（changes 接口 + 水位线推进）
java -jar dist/jars/tmdb-ingestion-*-all.jar incremental --entity movie --entity tv

# 热度轮询（trending / popular）
java -jar dist/jars/tmdb-ingestion-*-all.jar popularity --entity movie --window day
```

关键行为：

- **限流**：令牌桶 40 req/s（低于 TMDB 约 50 req/s 上限），429 时按响应头 `Retry-After` 等待后重试；
- **重试**：5xx / IO 异常指数退避（1s→2s→4s→8s，上限 30s）；4xx 直接失败不重试；
- **断点续传**：全量进度存 `_state/full_load_{entity}.json`，每 500 条推进一次；增量按实体类型保存水位线，
  只有整段窗口处理成功才推进，失败则下次重放；
- **幂等**：事件 ID 由内容哈希生成，重复投递可被下游去重；写入 ClickHouse 采用分区原子替换；
- **原始区**：`raw/{entity}/dt=yyyy-MM-dd/part-*.ndjson.gz`，TMDB 报文原样保留，清洗统一放在 DWD 层。

## 本地一键启动

前置：Docker Desktop（含 Compose v2）、JDK 11~17、Maven 3.8+。首次启动约需 15~30 分钟（拉取镜像并构建自定义镜像）。

```bash
make up-minimal                 # 核心 + OLAP + 图库 + 计算 + TMDB Mock
make ps                         # 查看服务状态
make logs SVC=clickhouse        # 查看单个服务日志
scripts/teardown.sh             # 停止（保留数据卷）
scripts/teardown.sh --purge     # 停止并删除数据卷
```

包含组件（括号内为宿主机端口）：

| 组件 | 地址 | 说明 |
| --- | --- | --- |
| MinIO | 9000 / 控制台 9001 | 数据湖（raw / warehouse / _state）+ ClickHouse 冷存储盘 |
| Kafka | 9095（容器内 `kafka:9092`） | KRaft 单节点，4 个 Topic |
| Hive Metastore | 9083 | Iceberg HiveCatalog 元数据（PostgreSQL 后端） |
| Spark | 7077 / UI 8085 / History 18080 | Standalone，REST 提交 6066 |
| Flink | UI 8081 | JobManager + TaskManager，RocksDB + MinIO checkpoint |
| ClickHouse | HTTP 8123 / Native 9002 / 指标 9363 | 1 分片 1 副本 + Keeper，冷热分层已配置 |
| Neo4j | 7474 / Bolt 7687 | 预装 APOC + GDS |
| Airflow | 8080 | 由内置 JRE 提交 Java CLI，通过 REST 提交 Spark / Flink |
| Prometheus / Grafana / Pushgateway | 9090 / 3000 / 9091 | 预置告警规则与平台总览大盘 |
| Kafka UI / kafka-exporter | 8087 / 9308 | Topic 与消费组积压观测 |
| WireMock | 8089 | TMDB 模拟（`TMDB_MOCK_MODE=true` 时生效） |

### TMDB Mock 数据

`deploy/compose/conf/wiremock` 下提供了可直接跑通全链路的模拟数据（无需 API Key）：

- 8 部电影 + 2 部剧集 + 29 位人物 + 14 家公司 + 2 个系列，含演职员、关键词、分国家上映信息；
- 故意保留的边界样例：详情 404 的已删除 ID、`budget=0`、同一人物两个不同 ID、剧集关键词 `results` 与电影 `keywords` 两种结构；
- 故障注入：`/3/movie/157336` 首次请求返回 429 + `Retry-After`，`/3/movie/1124` 首次返回 503，之后恢复（验证限流退避与重试）；
- changes 接口分页（第 2 页含已删除 ID）、trending / popular 榜单每次请求热度随机（用于实时飙升检测）；
- 重置故障场景状态：`make mock-reset`。

> 注意：真实 TMDB 的每日 ID 导出文件是 gzip 压缩的 NDJSON，Mock 返回未压缩 NDJSON（仓库中不存放二进制文件），采集端按 gzip 魔数自动识别两种格式。

### 配置校验（无需 Docker）

```bash
make validate-config
```

校验 compose 结构与引用完整性、XML / JSON / YAML 格式、Mock 映射与夹具的一致性、Shell 脚本语法。

## 工程规范

- 版本统一锁定在父 `pom.xml`，子模块不声明版本；Spark / Flink / Hadoop 为 `provided`，fat-jar 中重定位 Jackson。
- Checkstyle 在 `validate` 阶段执行，违规即构建失败；公共类必须有类级 Javadoc。
- 单元测试 `*Test`（Surefire），集成测试 `*IT`（Failsafe，`-Pit`）；`tmdb-common` 行覆盖率门禁 80%。
# tmdb_warehouse_graph

## 运行

```bash
make env-init                                   # 由 .env.example 生成 .env
make up                                         # 启动（core,olap,graph,compute,mock）
make wait && make init                          # 等待健康并初始化
make smoke                                      # 端到端冒烟

make ingest MODE=incremental DATE=2026-09-30    # 采集
make offline DATE=2026-09-30 SYNC=--sync        # 离线分层 + 同步 ClickHouse
make realtime JOB=popularity-trend              # 提交实时作业
make graph SUB=stats                            # 图统计
make governance SUB=quality ARGS=2026-09-30     # 质量检查
```

## 文档

完整设计文档见 [`docs/`](docs/README.md)：

| 文档 | 内容 |
| --- | --- |
| [01-architecture.md](docs/01-architecture.md) | 总体架构、技术选型与设计权衡 |
| [02-data-model.md](docs/02-data-model.md) | 分层模型、维度建模、SCD2 |
| [03-offline.md](docs/03-offline.md) | 离线链路、幂等与分区替换 |
| [04-realtime.md](docs/04-realtime.md) | 实时语义、去重、窗口与飙升检测 |
| [05-graph.md](docs/05-graph.md) | 图模型、装载与分析用例 |
| [06-governance.md](docs/06-governance.md) | 质量、血缘、生命周期、权限、指标口径 |
| [07-deployment.md](docs/07-deployment.md) | Compose 与 Helm 部署、资源基线 |
| [08-operations.md](docs/08-operations.md) | 日常作业、监控告警、备份恢复 |
| [09-development.md](docs/09-development.md) | 模块结构、测试策略、编码规范 |
| [10-data-dictionary.md](docs/10-data-dictionary.md) | 各层表与字段 |
| [11-metrics.md](docs/11-metrics.md) | 指标口径与边界 |
| [12-troubleshooting.md](docs/12-troubleshooting.md) | 故障排查手册 |
