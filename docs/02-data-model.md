# 02 · 数据模型与分层设计

## 2.1 分层原则

| 层 | 定位 | 可否被外部直接查询 | 数据形态 |
|---|---|---|---|
| ODS | 贴源：与上游结构一致，保留原始报文 | 不建议（仅供排障） | 明细 + 原始 JSON |
| DWD | 明细：清洗、规范化、维度建模 | 可以（需理解有效期语义） | 明细（维度 + 事实 + 桥接） |
| DWS | 汇总：主题域宽表 | 推荐 | 按维度聚合 |
| ADS | 应用：面向报表与接口 | 推荐 | 结果表（含排名） |
| RT | 实时：分钟级结果 + 事件落湖 | 可以 | 明细 + 窗口聚合 |

**铁律：**

1. **下游只能依赖上游同层或更低层**，禁止 DWS 直接读 ODS 原始 JSON（口径必须落在 DWD）。
2. **每一层都必须可重跑**：结果只依赖输入数据与业务日期，不依赖执行次数与执行时刻。
3. **分区统一为业务日期 `dt`**（维度表按有效期分区，见 2.4）。

## 2.2 ODS 贴源层

实体类型：`movie` / `tv` / `person` / `collection` / `company` / `keyword` / `network`。

对象存储路径：

```
s3://tmdb-lake/raw/{entity}/dt=yyyy-MM-dd/*.ndjson.gz
```

单条记录结构（`RawRecord`）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `schema_version` | int | 记录结构版本 |
| `entity_type` | string | movie / tv / person ... |
| `entity_id` | long | TMDB 实体 ID |
| `dt` | string | 业务日期 |
| `ingest_time` | long | 进入平台时间（epoch ms） |
| `source` | string | 来源（full / changes / popularity / trending） |
| `payload` | string | 原始 JSON（不做任何加工） |

ClickHouse 侧对应 `ods.ods_change_event` / `ods.ods_popularity_event`（实时落湖备份），
以及实体明细表（离线按 dt 分区，保留 payload 原文）。

**为什么保留原始 JSON**：上游结构变化时，可以基于原始报文重放新口径，
不需要重新采集历史数据（重新采集意味着再打一次 API，成本高且可能被限流）。

## 2.3 DWD 明细层

### 2.3.1 清洗规则（集中在 `DwdTransform`）

| 规则 | 原因 |
|---|---|
| `budget = 0` / `revenue = 0` → NULL | TMDB 用 0 表示"未录入"，参与均值与 ROI 会把结果拉向 0 |
| 日期字符串 → Date，非法值 → NULL | 上游存在空串与非法日期 |
| `adult` 布尔 → UInt8 | ClickHouse 无 boolean 存储类型 |
| 公司名去掉 `(US)` 之类后缀 | 同一公司在不同国家有不同后缀名，会导致维度膨胀 |
| 演员 / 职员按 `credit_id` 去重 | 同一人在同一部作品中可能担任多职（导演兼编剧），不能用 (movie_id, person_id) 去重 |
| 上映类型编码 → 中文名 | 报表直接可读，避免每个报表各翻译一遍 |

### 2.3.2 维度表

| 表 | 粒度 | 主键（ORDER BY） |
|---|---|---|
| `dwd.dim_movie` | 电影 × 版本（SCD2） | movie_id, valid_from |
| `dwd.dim_person` | 人物 × 版本（SCD2） | person_id, valid_from |
| `dwd.dim_genre` | 类型（区分 movie / tv） | media_type, genre_id |
| `dwd.dim_company` | 公司 | company_id |
| `dwd.dim_keyword` | 关键词 | keyword_id |
| `dwd.dim_country` | 国家 | country_code |
| `dwd.dim_language` | 语言 | language_code |
| `dwd.dim_date` | 日期（一次性生成多年） | dt |

### 2.3.3 事实表

| 表 | 粒度 | 说明 |
|---|---|---|
| `dwd.fact_movie_credit` | credit_id | 演职员（区分 cast / crew，导演标记） |
| `dwd.fact_movie_daily_snapshot` | dt × movie_id | 每日热度与评分快照 |
| `dwd.fact_movie_release` | dt × movie_id × 国家 × 上映方式 | 分国家上映 |
| `dwd.fact_change_event` | dt × event_id | 变更事件（来自增量采集） |

### 2.3.4 桥接表

多对多关系用桥接表表达，避免维度表膨胀：

- `dwd.bridge_movie_genre`（movie × genre）
- `dwd.bridge_movie_company`（movie × company）
- `dwd.bridge_movie_country`（movie × country）
- `dwd.bridge_movie_keyword`（movie × keyword）

## 2.4 SCD2 缓慢变化维

**为什么需要**：TMDB 的 `popularity` / `vote_count` / `revenue` 持续变化。
若维度直接覆盖更新，历史事实会"回溯变形"——同一份报表今天重跑和昨天跑结果不同，无法对账。

**模型**：

```
movie_sk    代理键（hash(movie_id, valid_from)，同一版本稳定）
movie_id    自然键
...业务列...
valid_from  版本生效时间（业务日期零点）
valid_to    失效时间；2099-12-31 表示当前有效
```

**合并语义**：

1. 内容哈希（业务列拼接）相同的实体**不产生新版本**——否则每天为几十万部电影各写一行；
2. 内容变化时：旧版本 `valid_to` 收敛到新版本 `valid_from`，新版本 `valid_to = 2099-12-31`；
3. 区间取闭区间 `[valid_from, valid_to]`，相邻版本首尾相接、不重叠不留空洞；
4. 幂等：同一业务日期重复执行结果一致（判定只依赖内容，不依赖执行次数）；
5. `is_current` 不单独维护，由 `valid_to = 2099-12-31` 推导（避免两个字段互相矛盾）。

**查询当前有效版本**：

```sql
SELECT * FROM dwd.dim_movie WHERE valid_to = toDateTime('2099-12-31 00:00:00');
```

**关联历史事实**（按事实发生时间取当时有效的版本）：

```sql
SELECT ...
FROM dwd.fact_movie_daily_snapshot f
JOIN dwd.dim_movie d
  ON d.movie_id = f.movie_id
 AND f.dt >= toDate(d.valid_from)
 AND f.dt <= toDate(d.valid_to);
```

## 2.5 DWS 汇总层

| 表 | 粒度 | 关键指标 |
|---|---|---|
| `dws.dws_movie_metric_1d` | dt × movie | 热度、7 日环比、评分、票房、预算、ROI、演职员规模 |
| `dws.dws_genre_year_metric` | dt × 类型 × 上映年 | 影片数、平均热度、平均评分、总票房 |
| `dws.dws_person_career` | dt × 人物 | 作品数、导演次数、出演次数、首末作品年、平均评分 |
| `dws.dws_country_year_metric` | dt × 国家 × 年 | 影片数、总票房、平均评分 |
| `dws.dws_company_finance` | dt × 公司 | 影片数、总预算、总票房、平均 ROI、平均评分 |

**环比口径**：`popularity_wow = (今日热度 - 7 日前热度) / 7 日前热度`。
无历史数据时记 0（而不是 NULL），避免报表出现空值与聚合失真。

**ROI 口径**：`roi = revenue / budget`，预算为 0（未知）时必须为 NULL，
且 ROI 排行表只纳入两者都已知的记录——否则"预算未知"的电影会污染排名。

## 2.6 ADS 应用层

| 表 | 用途 | 排序 |
|---|---|---|
| `ads.ads_top_movie` | 热门电影榜 | 按热度排名 |
| `ads.ads_genre_trend` | 类型趋势（含周环比） | 按类型 |
| `ads.ads_roi_ranking` | 投资回报排行 | 按 ROI 排名 |
| `ads.ads_person_influence` | 人物影响力榜（含图算法回流字段 pagerank） | 按综合分排名 |

影响力综合分（口径集中在 `AdsTransform.personInfluence`，便于调整）：

```
influence_score = popularity + work_count × 2 + avg_vote_average × 10
```

## 2.7 RT 实时层

| 表 | 粒度 | 说明 |
|---|---|---|
| `rt.rt_movie_popularity` | 实体 × 窗口 | 窗口聚合结果（ReplacingMergeTree，version 取窗口结束时间） |
| `rt.rt_popularity_event` | event_id | 原始热度事件（ReplacingMergeTree） |
| `rt.rt_surge_alert` | alert_id | 飙升告警（ID 含窗口，重放不重复） |
| `rt.rt_popularity_hourly` / `rt.rt_popularity_daily` | 物化视图 | 由明细自动聚合 |

## 2.8 命名与类型规范

- 表名：`{层}.{前缀}_{主题}_{粒度}`，如 `dws_movie_metric_1d`（1d = 日粒度汇总）。
- 字段名：小写下划线；布尔统一用 `is_`/`has_` 前缀；金额统一为整数最小单位（避免浮点误差）。
- 时间：`dt`（Date，分区）与 `event_time`（DateTime，业务时刻）区分开——
  混用会导致"分区日期"和"事件发生时间"对不上，是数仓最常见的 bug 来源之一。
- 可空性：明确区分"未知"（NULL）与"零"（0）。TMDB 的 0 大量表示未知，必须在 DWD 层清洗。
