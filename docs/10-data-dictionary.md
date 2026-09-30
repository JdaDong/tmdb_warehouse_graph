# 10 · 数据字典

字段以 ClickHouse 迁移脚本为准（`tmdb-governance/src/main/resources/clickhouse/migrations/`）。
下表列出各层主要表的核心字段与口径说明。

## 10.1 ODS 贴源层

### ods.ods_change_event（变更事件，实时落湖）

| 字段 | 类型 | 说明 |
|---|---|---|
| `dt` | Date | 业务分区日期 |
| `event_id` | String | 事件 ID（内容哈希，幂等基础） |
| `entity_type` | LowCardinality(String) | movie / tv / person |
| `entity_id` | UInt64 | 实体 ID |
| `event_time` | DateTime | 业务事件时间 |
| `payload` | String | 事件 JSON（EventEnvelope） |
| `ingest_time` | DateTime | 进入平台时间 |
| `schema_version` | UInt16 | 记录结构版本 |

引擎 `ReplacingMergeTree(ingest_time)`，按 `(dt, event_id)` 去重；TTL 90 天转冷。

### ods.ods_popularity_event（热度事件，实时落湖）

字段同上，来源为热度榜单轮询。

> 贴源实体明细表（`ods.ods_movie_raw` 等）保留原始 payload，
> 结构与上游一致，用于重放与排障，不直接对外提供查询。

## 10.2 DWD 明细层

### dwd.dim_movie（SCD2）

| 字段 | 类型 | 说明 |
|---|---|---|
| `movie_sk` | Int64 | 代理键（hash(movie_id, valid_from)） |
| `movie_id` | UInt64 | 自然键 |
| `title` / `original_title` | String | 标题 / 原始标题 |
| `original_language` | LowCardinality(String) | 原始语言 |
| `overview` | String | 简介 |
| `status` | LowCardinality(String) | 上映状态 |
| `release_date` | Nullable(Date) | 上映日期 |
| `release_year` | Nullable(UInt16) | 上映年份（由 release_date 派生） |
| `runtime` | Nullable(UInt32) | 片长（分钟） |
| `budget` | Nullable(UInt64) | 预算；**TMDB 的 0 已清洗为 NULL** |
| `revenue` | Nullable(UInt64) | 票房；同上 |
| `popularity` | Float64 | 热度 |
| `vote_average` | Nullable(Float64) | 评分 |
| `vote_count` | Nullable(UInt32) | 评分人数 |
| `adult` | UInt8 | 成人内容（布尔转 UInt8） |
| `collection_id` | Nullable(UInt64) | 所属系列 |
| `valid_from` | DateTime | 版本生效时间 |
| `valid_to` | DateTime | 失效时间（2099-12-31 = 当前有效） |
| `load_time` | DateTime | 装载时间（固定为业务日期零点，保证幂等） |

### dwd.dim_person（SCD2）

`person_sk`、`person_id`、`name`、`gender`（male/female/non-binary/unknown）、
`birthday`、`deathday`、`place_of_birth`、`known_for_department`、`popularity`、`adult`、
`valid_from`、`valid_to`、`load_time`。

### dwd.dim_genre / dim_company / dim_keyword / dim_country / dim_language / dim_date

| 表 | 主键 | 说明 |
|---|---|---|
| `dim_genre` | media_type + genre_id | 电影与剧集的类型 ID 空间不同，用 media_type 区分 |
| `dim_company` | company_id | 公司名已去除 `(US)` 之类后缀 |
| `dim_keyword` | keyword_id | 详情接口只返回关键词名，ID 用名称的稳定哈希 |
| `dim_country` | country_code | 国家编码 |
| `dim_language` | language_code | 语言编码 |
| `dim_date` | dt | 日期维（含年 / 季 / 月 / 日 / 周 / 星期 / 是否周末） |

### dwd.fact_movie_credit

| 字段 | 说明 |
|---|---|
| `dt` | 业务日期 |
| `credit_id` | 演职员记录 ID（**粒度**：同一人可任多职） |
| `movie_id` / `person_id` | 关联维度 |
| `credit_type` | cast / crew |
| `department` / `job` | 部门 / 职位 |
| `character_name` | 角色名（cast） |
| `cast_order` | 出演顺序 |
| `is_director` | 是否导演（job = Director） |

### dwd.fact_movie_daily_snapshot

`dt`、`movie_id`、`title`、`popularity`、`vote_average`、`vote_count`、`revenue`、`release_date`。
对每个 `(dt, movie_id)` 唯一（重复采集只保留一条）。

### dwd.fact_movie_release

`dt`、`movie_id`、`country_code`、`release_type`（首映 / 限定上映 / 院线 / 数字 / 实体 / 电视 / 其他）、
`release_date`、`certification`（分级）。

## 10.3 DWS 汇总层

### dws.dws_movie_metric_1d

| 字段 | 说明 |
|---|---|
| `dt`, `movie_id`, `title` | 粒度：日 × 电影 |
| `release_year` | 上映年份 |
| `popularity` | 热度 |
| `popularity_wow` | 7 日环比；无历史记 0 |
| `vote_average` / `vote_count` | 评分 / 评分人数 |
| `revenue` / `budget` | 票房 / 预算（0 已转 NULL） |
| `roi` | revenue / budget；预算未知为 NULL |
| `cast_count` / `crew_count` | 演员数 / 职员数 |

### dws.dws_genre_year_metric / dws.dws_country_year_metric / dws.dws_company_finance / dws.dws_person_career

| 表 | 粒度 | 主要指标 |
|---|---|---|
| `dws_genre_year_metric` | dt × 类型 × 上映年 | movie_count、avg_popularity、avg_vote_average、total_revenue |
| `dws_country_year_metric` | dt × 国家 × 年 | movie_count、total_revenue、avg_vote_average |
| `dws_company_finance` | dt × 公司 | movie_count、total_budget、total_revenue、avg_roi、avg_vote_average |
| `dws_person_career` | dt × 人物 | work_count、as_director_count、as_cast_count、first_work_year、latest_work_year、avg_vote_average |

## 10.4 ADS 应用层

| 表 | 字段 | 说明 |
|---|---|---|
| `ads_top_movie` | dt, rank, movie_id, title, release_year, popularity, vote_average, vote_count, revenue, roi | 热门电影榜（按热度） |
| `ads_genre_trend` | dt, genre_id, genre_name, movie_count, avg_popularity, avg_vote_average, popularity_wow | 类型趋势 |
| `ads_roi_ranking` | dt, rank, movie_id, title, budget, revenue, roi, vote_average | ROI 排行（仅纳入预算与票房都已知的记录） |
| `ads_person_influence` | dt, rank, person_id, person_name, known_for_department, influence_score, pagerank, work_count, avg_vote_average | 人物影响力（pagerank 由图计算回流） |

## 10.5 RT 实时层

| 表 | 字段 | 说明 |
|---|---|---|
| `rt_movie_popularity` | entity_id, event_time, popularity, vote_average, vote_count, title, list_name, ingest_time, version | 窗口聚合结果（version = 窗口结束时间） |
| `rt_popularity_event` | event_id, entity_type, entity_id, event_time, popularity, rank, list_name, ingest_time | 原始热度事件 |
| `rt_surge_alert` | alert_id, entity_type, entity_id, title, window_start, window_end, baseline_popularity, current_popularity, growth_ratio, alert_time | 飙升告警（alert_id 含窗口，重放不重复） |
| `rt_popularity_hourly` / `rt_popularity_daily` | 物化视图 | 由明细自动聚合 |

## 10.6 治理库

| 表 | 用途 |
|---|---|
| `governance.metadata_snapshot` / `metadata_column_snapshot` | 表 / 字段元数据快照 |
| `governance.standard_check_result` | 命名规范、注释完备度、必备字段检查 |
| `governance.dq_result` | 质量检查结果（rule_id、severity、passed、metric_value、threshold_value） |
| `governance.lineage_edge` | 血缘边（src → dst、job、edge_type） |
| `governance.lifecycle_execution` | 生命周期执行记录（policy、action、status、detail） |
| `governance.query_cost_report` | 慢查询与成本分析 |
