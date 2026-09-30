# 07 · 部署

## 7.1 Docker Compose（本地 / 单机）

### 前置条件

- Docker 20.10+ 与 Compose v2（`docker compose version`）
- 可用内存 ≥ 16GB（全部组件），最小组合 ≥ 8GB
- 磁盘 ≥ 100GB（含镜像与数据卷）

### 启动

```bash
make env-init     # 由 .env.example 生成 .env（自动填充随机口令）
make up           # 启动（默认 core,olap,graph,compute,mock）
make wait         # 等待服务健康（默认最多 300 秒）
make ps           # 查看状态
make init         # 初始化 Iceberg / ClickHouse / Neo4j
make smoke        # 端到端冒烟
```

可用 profile（通过 `PROFILES` 覆盖）：

| Profile | 组件 |
|---|---|
| `core` | MinIO、Kafka、Postgres、Hive Metastore |
| `olap` | ClickHouse（+ Keeper）、OpenMetadata（可选） |
| `graph` | Neo4j |
| `compute` | Spark（master/worker）、Flink（jobmanager/taskmanager） |
| `mock` | WireMock（TMDB Mock，无 API Key 时使用） |
| `orchestration` | Airflow（webserver/scheduler） |
| `monitoring` | Prometheus、Pushgateway、Grafana、Kafka Exporter、Kafka UI |

```bash
PROFILES=core,olap,graph,compute,mock,orchestration,monitoring make up
```

### 目录结构

```
deploy/compose/
├── docker-compose.yml          主编排（按 profile 分组）
└── conf/                       各组件配置（挂载进容器）
    ├── clickhouse/             config.d / users.d
    ├── clickhouse-keeper/
    ├── hadoop/                 core-site.xml（S3A）
    ├── hive/                   hive-site.xml（Metastore）
    ├── spark/                  spark-defaults.conf
    ├── postgres/initdb/        建库脚本
    ├── prometheus/             抓取配置 + 告警规则
    ├── grafana/                数据源与仪表盘
    ├── minio/                  策略与生命周期
    ├── openmetadata/
    └── wiremock/               TMDB Mock（mappings + __files）
```

### 端口

| 服务 | 端口 | 说明 |
|---|---|---|
| MinIO | 9000 / 9001 | API / Console |
| Kafka | 9092 | 客户端 |
| ClickHouse | 8123 / 9000 | HTTP / Native |
| Neo4j | 7474 / 7687 | Browser / Bolt |
| Spark Master | 8080 | UI |
| Flink JobManager | 8081 | UI |
| Airflow | 8080 | Webserver |
| Prometheus | 9090 | — |
| Grafana | 3000 | 默认 admin/admin |
| Kafka UI | 8080 | — |

## 7.2 Kubernetes（Helm）

```bash
# 1) 准备 Secret（不要把口令写进 values.yaml）
kubectl create secret generic tmdbwh-secrets \
  --from-literal=clickhouse-password='...' \
  --from-literal=neo4j-password='...' \
  --from-literal=s3-access-key='...' \
  --from-literal=s3-secret-key='...' \
  --from-literal=airflow-fernet-key='...' \
  --from-literal=airflow-webserver-secret-key='...' \
  --from-literal=grafana-admin-password='...'

# 2) 安装
helm install tmdbwh deploy/helm/tmdbwh -n tmdbwh --create-namespace

# 3) 初始化（顺序不能颠倒：表结构 → 图约束）
kubectl -n tmdbwh run cli --rm -it --restart=Never \
  --image=tmdbwh/cli:1.0.0 -- java -jar /opt/tmdbwh/dist/governance.jar migrate
kubectl -n tmdbwh run cli --rm -it --restart=Never \
  --image=tmdbwh/cli:1.0.0 -- java -jar /opt/tmdbwh/dist/graph.jar init
```

**定位与限制**（详见 `deploy/helm/tmdbwh/README.md`）：
本 Chart 面向"单集群一体化"部署，便于把 Compose 环境搬到 K8s。
生产建议拆分为多个 Chart，有状态组件使用官方 Chart / Operator
（ClickHouse、Kafka、MinIO、Flink、Airflow、监控）。

## 7.3 资源基线

| 组件 | CPU | 内存 | 磁盘 | 说明 |
|---|---|---|---|---|
| ClickHouse | 4–8C | 16–32G | 500G SSD（热）+ 2T（冷） | 内存不足会频繁 OOM，务必留足 |
| Kafka | 2–4C | 8–16G | 200G × 保留期 | 保留 7 天足够排障重放 |
| Spark Master | 1C | 2G | — | — |
| Spark Worker ×2 | 2–4C | 8–16G | — | 决定离线作业并发 |
| Flink JobManager | 2C | 4G | — | 大状态作业需更大内存 |
| Flink TaskManager ×2 | 2–4C | 8–16G | 本地盘（RocksDB） | 总槽数需 ≥ 实时作业并行度 |
| Neo4j | 2–4C | 8–16G | 100G | 页缓存建议内存的 50%~60% |
| MinIO | 2C | 4G | 200G+ | — |
| Airflow | 2C | 4G | 50G | LocalExecutor 仅适合小规模 |
| Postgres（Metastore） | 2C | 4G | 50G | — |

## 7.4 配置与密钥

**分层配置**（HOCON）：

1. `reference.conf`（各模块内置默认值）——不要改，改了会影响所有人；
2. `config/application.conf`（部署级覆盖，随环境分发）；
3. 环境变量（最高优先级）：`CLICKHOUSE_URL`、`S3_*`、`KAFKA_*`、`NEO4J_*`、`TMDB_*` 等；
4. `--config` 指定的文件（CLI 参数）。

**密钥一律通过环境变量注入**，不写进任何文件（`.env` 已在 `.gitignore` 中）。
启动时配置一次性校验，缺失会列出全部问题而不是逐条试错。

## 7.5 上线检查清单

- [ ] `.env` 中所有 `CHANGE_ME` 已替换；
- [ ] `make validate-config` 通过（配置 / XML / JSON / SQL / Mock 引用）；
- [ ] `governance migrate` 已执行且无待应用脚本；
- [ ] ClickHouse 集群名与迁移脚本的 `ON CLUSTER` 一致；
- [ ] Flink 总槽数 ≥ `tmdbwh.realtime.parallelism`；
- [ ] `S3_LAKE_URL` 用的是容器内可达地址（ClickHouse 读取湖仓结果依赖它）；
- [ ] 监控告警已接入（至少：ClickHouse 存活、Flink 作业数、实时数据滞后）；
- [ ] 生命周期默认 dry-run，确认后才 `--apply`；
- [ ] 冒烟测试 `make smoke` 通过。
