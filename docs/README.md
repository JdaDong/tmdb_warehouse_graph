# TMDB 数据仓库与知识图谱平台 · 文档

以 TMDB 为数据源，构建**离线数仓 + 实时数仓 + OLAP 数据治理 + 图数据库**的一体化数据平台。
所有组件均可本地一键启动并真实运行（无 TMDB API Key 时用内置 Mock 跑通全链路）。

## 文档索引

| 文档 | 内容 | 读者 |
|---|---|---|
| [01-architecture.md](01-architecture.md) | 总体架构、技术选型、数据流与设计权衡 | 架构师、新加入者 |
| [02-data-model.md](02-data-model.md) | 分层模型（ODS/DWD/DWS/ADS）、维度建模、SCD2、字典表 | 数据建模、开发 |
| [03-offline.md](03-offline.md) | Spark 离线链路：解析、清洗、聚合、湖仓写入、ClickHouse 同步 | 离线开发 |
| [04-realtime.md](04-realtime.md) | Flink 实时链路：去重、窗口聚合、飙升检测、语义与幂等 | 实时开发 |
| [05-graph.md](05-graph.md) | Neo4j 图谱：建模、装载、分析用例、结果回流 | 图分析、开发 |
| [06-governance.md](06-governance.md) | 数据治理：元数据血缘、质量、生命周期、权限、指标口径 | 治理、数据 owner |
| [07-deployment.md](07-deployment.md) | 部署：Docker Compose 与 Kubernetes Helm、资源基线 | 运维、SRE |
| [08-operations.md](08-operations.md) | 运维手册：监控告警、日常作业、备份恢复、扩容 | 运维、值班 |
| [09-development.md](09-development.md) | 开发规范：模块结构、测试策略、代码风格、发布流程 | 全体开发 |
| [10-data-dictionary.md](10-data-dictionary.md) | 数据字典：各层表结构与字段说明 | 分析、开发 |
| [11-metrics.md](11-metrics.md) | 指标口径：定义、算法、负责人口径边界 | 分析、业务 |
| [12-troubleshooting.md](12-troubleshooting.md) | 故障排查手册：典型问题与处置步骤 | 值班、开发 |

## 快速开始

```bash
make env-init      # 由 .env.example 生成 .env（自动填充随机口令）
make up            # 启动最小可用组合（core,olap,graph,compute,mock）
make wait          # 等待服务健康
make init          # 初始化 Iceberg / ClickHouse / Neo4j
make smoke         # 端到端冒烟：采集 → 离线 → 图装载 → 质量检查
```

无 TMDB API Key 时平台使用内置 WireMock 作为数据源（`mock` profile），
链路行为与真实 TMDB 一致（含 429 限流、偶发 5xx、404 等场景）。

## 阅读顺序建议

- **新同学**：01 → 02 → 09 → 03；
- **要做实时**：01 → 04 → 08；
- **要做治理**：01 → 06 → 11；
- **要部署上线**：07 → 08 → 12。
