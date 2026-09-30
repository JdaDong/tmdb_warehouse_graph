# 09 · 开发规范

## 9.1 模块结构

```
tmdb-common       公共：配置 / 模型 / 工具 / 对象存储 / ClickHouse 客户端（不产出 jar）
tmdb-ingestion    采集：全量 / 增量 / 热度轮询
tmdb-offline      离线：Spark 分层 + 湖仓写入 + ClickHouse 同步
tmdb-realtime     实时：Flink 作业
tmdb-graph        图：Neo4j 装载与分析
tmdb-governance   治理：迁移 / 质量 / 血缘 / 生命周期 / 权限 / 指标
```

每个模块的结构：

```
tmdb-<module>/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/com/tmdbwh/<module>/...
    │   └── resources/
    │       ├── reference.conf          模块默认配置（会被合并进全局配置）
    │       └── sql/ ...                资源型 SQL
    └── test/
        ├── java/com/tmdbwh/<module>/...
        └── resources/...
```

## 9.2 构建

```bash
make build                      # 编译 + Checkstyle + 单测 + 覆盖率门禁 + 打包
make build MODULE=tmdb-common   # 只构建单个模块（含其依赖）
make build MVN_MIRROR=cn        # 无法直连 Maven Central 时用国内镜像
make package                    # 只打包（跳过测试与规范检查）
make test MODULE=tmdb-offline   # 只跑单测
make it MODULE=tmdb-common      # 单测 + Testcontainers 集成测试（需要 Docker）
make lint                       # Checkstyle
make fmt / make fmt-check       # google-java-format（AOSP 风格）
make coverage                   # 覆盖率报告
```

**JDK 版本**：统一用 JDK 17 构建（产物为 Java 11 字节码）。
Spark 3.5 / Flink 1.18 官方只支持到 JDK 17；用更高版本会被 enforcer 拦下并提示切换。
macOS 下 `make` 会自动探测 JDK 17，其他系统用 `BUILD_JAVA_HOME=/path/to/jdk17` 指定。

## 9.3 质量门禁

| 门禁 | 要求 | 失败后果 |
|---|---|---|
| Checkstyle | 0 违规（含未使用导入） | 构建失败 |
| Javadoc | 公共类必须有类级注释 | 构建失败 |
| 行覆盖率 | `tmdb-common` ≥ 80%，其他模块 ≥ 60% | 构建失败 |
| 集成测试 | 无 Docker 时自动跳过（不失败） | — |

## 9.4 测试策略

**原则：能真跑就不 mock。**

| 场景 | 做法 |
|---|---|
| Spark 转换逻辑 | 用 `SparkSession.builder().master("local[*]")` 真跑真实报文 |
| SCD2 语义 | 构造内存 Dataset，验证版本链与幂等 |
| Flink 算子判定逻辑 | 判定抽成静态方法单测（避免依赖 Flink 测试工具） |
| Cypher 生成 | 断言语句文本（幂等性、参数化） |
| 需要 ClickHouse / Neo4j / MinIO | Testcontainers（`-Pit` profile，无 Docker 自动跳过） |
| 与外部系统交互的流程 | mock 客户端（如 Neo4jClient、ClickHouseClient）验证调用与容错 |

**测试命名**：`*Test`（单元测试）、`*IT`（集成测试，需 Docker）。

**必须覆盖的边界**（这些是历史上真实出过问题的点）：

- 上游字段缺失 / 非法值（空串日期、0 值预算）；
- 重复投递与重放（幂等）；
- 坏消息与未知枚举（不应崩溃）；
- 除零与 NULL（不应产生 NaN / Infinity）；
- 空输入（"今天没有变更"不应让链路失败）。

## 9.5 编码规范要点

- **注释写"为什么"而不是"做了什么"**：代码本身能说明做了什么，
  需要解释的是决策背景、踩过的坑、为什么不这么做。
- **清洗 / 口径集中在一处**：例如"TMDB 的 0 表示未知"只在 `DwdTransform` 处理一次，
  散落各处必然出现口径不一致。
- **不可变优先**：配置类、值对象用 `final` 字段。
- **异常要有明确语义**：`TmdbWhException` 体系区分配置错误、存储错误、重试耗尽等；
  不要用 `RuntimeException` 兜底所有情况。
- **日志**：结构化上下文（`LogContext`）带 job / dt / traceId；敏感信息必须脱敏（`Masking`）。
- **SQL 注入**：表名、分区表达式只能走白名单校验（`ClickHouseSql.identifier` 等），禁止拼接用户输入。

## 9.6 配置约定

- 默认值写在各模块 `reference.conf`；
- 部署级覆盖写在 `config/application.conf`；
- 密钥只通过环境变量注入（`${?ENV_VAR}` 语法）；
- 新增配置项必须同时更新 `.env.example` 与相关文档。

## 9.7 发布流程

1. 本地：`make build` + `make ci`（静态校验）；
2. 提交 PR（CI 会跑 validate / build / integration / smoke）；
3. 合并后由 CI 产出 fat-jar 与镜像；
4. 部署：先 `governance migrate`（表结构），再滚动更新作业；
5. 观察：监控大盘 + 质量检查结果，确认无回归后再关闭变更窗口。

## 9.8 添加新指标 / 新表的流程

1. **建模**：确认所属层与粒度，写进 `docs/02-data-model.md`；
2. **迁移脚本**：在 `tmdb-governance/.../migrations/` 新增 `V<n>__*.sql`（含 COMMENT）；
3. **作业逻辑**：在对应 Transform 实现（口径集中，不散落）；
4. **指标定义**：若是新指标，加入 `tmdbwh.governance.metrics.definitions`；
5. **数据字典**：更新 `docs/10-data-dictionary.md`；
6. **测试**：补单元测试（含边界）；
7. **血缘**：若新增表间依赖，补 `tmdbwh.governance.lineage.edges`。
