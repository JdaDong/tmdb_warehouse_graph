# Helm Chart：tmdbwh

把 Docker Compose 环境搬到 Kubernetes 的部署清单。

## 定位

本 Chart 面向"单集群一体化部署"，目标是让整套平台（对象存储、Kafka、Spark、Flink、ClickHouse、
Neo4j、Airflow、监控）在一个命名空间里跑起来，便于演示、联调与小规模生产。

**生产环境建议拆分为多个 Chart，有状态组件使用官方 Chart / Operator**：

| 组件 | 推荐方案 | 原因 |
|---|---|---|
| ClickHouse | Bitnami Chart / Altinity Operator | 分片副本编排、滚动升级、备份 |
| Kafka | Strimzi Operator | 分区再平衡、证书轮转、KRaft 管理 |
| MinIO | MinIO Operator | 多租户、纠删码、扩容 |
| Flink | Flink Kubernetes Operator | 作业生命周期、savepoint、自动伸缩 |
| Airflow | 官方 Chart | Celery/K8s Executor、Webserver 高可用 |
| 监控 | kube-prometheus-stack | 持久化、告警路由、高可用 |

本 Chart 的价值在于给出各组件的**依赖顺序、配置挂载点与资源基线**，
这些在任何部署形态下都是一样的。

## 安装

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
```

## 关键参数

| 参数 | 默认 | 说明 |
|---|---|---|
| `clickhouse.replicas` | 1 | 生产建议 ≥ 2（配合 Keeper） |
| `clickhouse.clusterName` | `tmdb_cluster` | 必须与迁移脚本的 `ON CLUSTER` 一致 |
| `flink.taskmanager.replicas` | 2 | 决定总槽数，需 ≥ 实时作业并行度 |
| `flink.checkpointDir` | `s3a://tmdb-lake/flink/checkpoints` | 存对象存储才能跨重启恢复 |
| `kafka.topics` | 4 个 Topic | 分区数按吞吐调整 |
| `neo4j.persistence.size` | 100Gi | 图数据可重建，容量要求低于数仓 |
| `externalS3.enabled` | false | 接外部 S3 时置 true 并填 endpoint/bucket |
| `config.tmdbMock` | true | 无 TMDB API Key 时用 Mock 跑通链路 |

## 校验

```bash
make helm-lint   # 等价于 helm lint deploy/helm/tmdbwh
helm template tmdbwh deploy/helm/tmdbwh | kubectl apply --dry-run=client -f -
```

## 已知限制

- Spark 使用 Standalone 集群：长任务会占满整个集群，生产请改用 Spark on K8s / Spark Operator。
- Airflow 使用 LocalExecutor：任务在 scheduler 容器内执行，任务量上来后需换 CeleryExecutor。
- ClickHouse 与 Kafka 单副本：不具备高可用，仅用于演示。
