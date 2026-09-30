# 05 · 图数据库（Neo4j）

## 5.1 图模型

**节点**

| 标签 | 主键 | 主要属性 |
|---|---|---|
| `Movie` | `movie_id` | title, release_year, runtime, popularity, vote_average, vote_count, budget, revenue |
| `TvShow` | `tv_id` | name, first_air_year, popularity, vote_average |
| `Person` | `person_id` | name, gender, known_for_department, popularity |
| `Genre` | `genre_id` | name, media_type（movie / tv 的类型 ID 空间不同） |
| `Company` | `company_id` | name |
| `Keyword` | `keyword_id` | name |
| `Country` | `country_code` | name |

**关系**

| 关系 | 起点 → 终点 | 属性 |
|---|---|---|
| `ACTED_IN` | Person → Movie | character_name, cast_order |
| `DIRECTED` | Person → Movie | — |
| `CREW_OF` | Person → Movie | department, job |
| `HAS_GENRE` | Movie → Genre | — |
| `PRODUCED_BY` | Movie → Company | — |
| `HAS_KEYWORD` | Movie → Keyword | — |
| `FROM_COUNTRY` | Movie → Country | — |

**约束与索引**：每个标签的主键建唯一性约束（`IF NOT EXISTS`，幂等），
Movie / TvShow / Person 另建 `name` 索引供模糊检索。

## 5.2 装载

```bash
java -jar graph.jar init                       # 约束与索引
java -jar graph.jar load                       # 从数仓装载（幂等 MERGE）
java -jar graph.jar load --label Movie --label Person
java -jar graph.jar load --clear --yes          # 清空后重建
java -jar graph.jar stats                       # 节点与关系统计
```

**顺序很重要**：先写全部节点，再写关系。
关系语句用 `MATCH` 找两端节点，先写关系会因为节点不存在而整批落空——
而且**不报错**，表现为"节点都在但关系为 0"，是最难排查的一类问题。

**幂等性**：全部使用 `MERGE + SET`，重复装载不产生重复节点 / 关系；增量直接重跑即可。

**数据来源可配置**（`tmdbwh.graph.queries`）：分层调整时改配置即可，不需要改代码重新发版。
SQL 必须用 `AS` 让列名与图属性名对齐（如 `genre_name AS name`）。

## 5.3 分析用例

```bash
java -jar graph.jar cases        # 列出全部用例
java -jar graph.jar analyze --case degree-centrality --param limit=10
java -jar graph.jar analyze --case similar-movies --param movie_id=27205
java -jar graph.jar analyze --case shortest-path --param from_id=525 --param to_id=6193
java -jar graph.jar analyze --case actor-network --param person_id=525
```

| 用例 | 说明 | 主要参数 |
|---|---|---|
| `degree-centrality` | 人物度数中心性（参与作品数） | limit |
| `top-coactors` | 合作最多的演员对 | limit |
| `director-actor-pairs` | 导演与演员的重复合作（"卡司班底"） | limit |
| `actor-network` | 指定演员的合作网络（一度） | person_id, limit |
| `shortest-path` | 两人之间的最短合作路径（1..6 跳） | from_id, to_id |
| `genre-cooccurrence` | 类型共现 | limit |
| `company-genre` | 制片公司的类型偏好 | limit |
| `similar-movies` | 相似电影推荐（共享演员 / 类型 / 关键词数） | movie_id, limit |
| `collaboration-circle` | N 度合作圈覆盖的作品数 | person_id, depth(1..4) |
| `keyword-centrality` | 关键词中心度（哪些题材是枢纽） | limit |

**为什么用参数化 Cypher 而不是 GDS 图算法库**：

- GDS 需要额外安装插件，社区版默认没有，装不上时整个分析功能不可用；
- 本项目的需求（度数、共现、路径、相似度）都能用 Cypher 表达，且结果可解释；
- 真需要 PageRank / 社区发现时，把对应 GDS 调用加到 `GraphAnalytics` 即可，调用方不用改。

**每条查询都带 LIMIT**：图查询容易在稠密节点上爆炸（一个演员几万条边），
不带限制的查询会拖垮整个实例。`shortest-path` 是唯一例外（天然单行），且跳数限制在 1..6。

## 5.4 结果回流

图分析结果写回 ClickHouse，供报表与接口使用：

- `ads.ads_person_centrality`：人物中心性（与 `ads_person_influence` 的 pagerank 字段对应）；
- `ads.ads_movie_similarity`：电影相似度（推荐召回）。

这样图计算的结果与数仓其他指标在同一处管理，避免"图里算一套、报表里另一套"。

## 5.5 数据一致性

图是**派生数据**：丢失后重跑 `graph.jar load` 即可重建，因此备份优先级低于 ClickHouse 与对象存储。

常见不一致与排查：

| 现象 | 原因 | 处置 |
|---|---|---|
| 关系数为 0 但节点都在 | 装载顺序错误，或 `from_id` / `to_id` 类型不一致（Long vs String） | 检查装载顺序与桥接表字段类型 |
| 节点数远少于预期 | 维度表只取了当前版本（`valid_to = 2099-12-31`），历史版本未装载（符合预期） | 确认是否需要历史版本 |
| 相同电影出现两个节点 | `movie_id` 在一个表里是 UInt64、另一个是 String | 统一字段类型 |
| 相似推荐结果不相关 | 关系装载不完整（例如 HAS_KEYWORD 桥接表缺失） | 检查 `graph.jar stats` 各关系数量 |

## 5.6 性能建议

- 页缓存设为可用内存的 50%~60%：图遍历极度依赖缓存命中率；
- 批量装载用 `UNWIND`（本项目默认）而不是逐条 commit，百万节点下逐条 commit 会跑几小时；
- 批量大小默认 1000，过大会让事务超时或内存暴涨；
- 稠密节点（超级节点）会让最短路径与相似度查询变慢，必要时限制跳数（本项目已限制 1..6）。
