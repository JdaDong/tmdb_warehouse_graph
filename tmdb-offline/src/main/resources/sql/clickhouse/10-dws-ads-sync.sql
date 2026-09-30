-- =============================================================================
-- 离线同步：把 Spark 写入湖仓（Iceberg / Parquet）的结果装载到 ClickHouse。
--
-- 执行顺序：
--   1) 先替换各事实 / 汇总表的业务日期分区（原子切换，重跑结果一致）；
--   2) 再重算 DWS / ADS 的汇总（这些表的数据是"按 dt 整体重算"的，同样先替换分区）。
--
-- 变量由 OfflineSqlRunner / 同步任务渲染：
--   ${dt}        业务日期 yyyy-MM-dd
--   ${cluster}   ON CLUSTER 子句（单机为空）
--   ${lake_url}  湖仓在对象存储上的根地址，如 http://minio:9000/tmdb-lake/warehouse/
--   ${ak}/${sk}  对象存储凭据（由环境变量注入，脚本中只出现占位符）
--
-- 注意：本文件中的 SQL 依赖 ClickHouse 对 s3() 表函数与 Parquet 的支持；
--       若目标环境没有对象存储访问权限，可改为由 Spark 通过 JDBC 直接写入（见 LakeTables 的注释）。
-- =============================================================================

-- ---------------------------------------------------------------- DWS：电影每日指标
INSERT INTO dws.dws_movie_metric_1d
SELECT
    toDate('${dt}')                                          AS dt,
    movie_id                                                 AS movie_id,
    title                                                    AS title,
    release_year                                             AS release_year,
    popularity                                               AS popularity,
    popularity_wow                                           AS popularity_wow,
    vote_average                                             AS vote_average,
    vote_count                                               AS vote_count,
    revenue                                                  AS revenue,
    budget                                                   AS budget,
    roi                                                      AS roi,
    cast_count                                               AS cast_count,
    crew_count                                               AS crew_count,
    now()                                                    AS load_time
FROM s3('${lake_url}dws.dws_movie_metric_1d/dt=${dt}/*.parquet', '${ak}', '${sk}', 'Parquet');

-- ---------------------------------------------------------------- ADS：热门电影榜
INSERT INTO ads.ads_top_movie
SELECT
    toDate('${dt}') AS dt,
    rank            AS rank,
    movie_id        AS movie_id,
    title           AS title,
    release_year    AS release_year,
    popularity      AS popularity,
    vote_average    AS vote_average,
    vote_count      AS vote_count,
    revenue         AS revenue,
    roi             AS roi,
    now()           AS load_time
FROM s3('${lake_url}ads.ads_top_movie/dt=${dt}/*.parquet', '${ak}', '${sk}', 'Parquet');

-- ---------------------------------------------------------------- ADS：ROI 排行
INSERT INTO ads.ads_roi_ranking
SELECT
    toDate('${dt}') AS dt,
    rank            AS rank,
    movie_id        AS movie_id,
    title           AS title,
    budget          AS budget,
    revenue         AS revenue,
    roi             AS roi,
    vote_average    AS vote_average,
    now()           AS load_time
FROM s3('${lake_url}ads.ads_roi_ranking/dt=${dt}/*.parquet', '${ak}', '${sk}', 'Parquet');
