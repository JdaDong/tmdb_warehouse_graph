# 11 · 指标口径

指标定义集中在 `tmdbwh.governance.metrics.definitions`（配置），
由 `governance metrics --validate` 校验表与字段是否真实存在。
**报表必须引用这里的定义**，禁止在 SQL 里各自实现。

## 11.1 指标清单

| 指标 | 出处表 | 计算表达式 | 负责人 |
|---|---|---|---|
| `movie_popularity` | `dws.dws_movie_metric_1d` | `avg(popularity)` | data-platform |
| `movie_vote_average` | `dws.dws_movie_metric_1d` | `avg(vote_average)` | data-platform |
| `movie_roi` | `ads.ads_roi_ranking` | `revenue / budget` | data-platform |
| `genre_movie_count` | `dws.dws_genre_year_metric` | `sum(movie_count)` | data-platform |
| `person_influence` | `ads.ads_person_influence` | `influence_score` | graph-team |

## 11.2 口径边界（必须遵守）

### 热度（popularity）

- 定义：TMDB popularity，随时间变化，每日快照；
- **环比是 7 日环比**（`popularity_wow`），不是日环比也不是月环比；
- 无历史数据时环比记 0（不是 NULL），避免报表空值与聚合失真；
- 实时口径为"5 分钟窗口均值"，与离线的"日快照"不同——对比时需说明口径。

### 评分（vote_average / vote_count）

- `vote_average` 是加权平均分（TMDB 内部算法），不是简单平均；
- 聚合时用 `avg(vote_average)`，不用 `sum(vote_average)/sum(vote_count)`（后者口径不同）；
- `vote_count` 取窗口内最大值（人数只增不减，取平均无意义）。

### 票房与预算（revenue / budget）

- **TMDB 的 0 表示"未录入"，不是 0 元**：DWD 层已清洗为 NULL；
- 因此 `avg(revenue)` 只在已知记录上计算，不会被大量 0 拉低；
- `budget` 来自维度当前有效版本（SCD2），不是事实表。

### ROI

- `roi = revenue / budget`；
- 预算或票房未知（NULL）时 ROI 为 NULL，**不参与 ROI 排行**；
- 分母为 0 时必须返回 NULL 而不是 Infinity（Infinity 会污染下游 avg / sum）。

### 影响力（person_influence）

```
influence_score = popularity + work_count × 2 + avg_vote_average × 10
```

- 权重集中在 `AdsTransform.personInfluence`，调整口径改一处；
- `pagerank` 字段由图计算（Neo4j）回流，缺省为 0；接入 GDS 后填充真实值。

## 11.3 一致性校验

`governance metrics --validate` 检查三类问题（都是"口径失控"的典型起因）：

1. **重名不同义**：同一指标名两种算法——加载阶段直接失败；
2. **表不存在**：指标引用的表被改名或删除；
3. **字段不存在**：表达式里的列在表里没有（表结构变更后常见，且不会报错、只会静默出错）。

新增指标流程见 `docs/09-development.md` §9.8。

## 11.4 常见问题

**Q：同一个"热度"在实时表和离线表里数值不一样？**

A：口径不同——实时是 5 分钟窗口均值，离线是当日快照。对比时应明确口径，
且实时数据允许 15 分钟滞后（超过则告警）。

**Q：为什么某些电影没有 ROI？**

A：TMDB 预算字段为 0（未录入）时已在 DWD 层清洗为 NULL，因此不参与 ROI 计算与排行。
若需要"包含未知预算"的口径，应显式另定义一个指标，而不是改现有定义。

**Q：环比为什么有时是 0？**

A：7 天前没有该电影的快照（首次出现或历史缺失）时记 0。
判断"真的没涨"应结合样本量与快照完整性，不要直接把 0 当作"持平"。
