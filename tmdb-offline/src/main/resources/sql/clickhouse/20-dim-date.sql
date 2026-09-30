-- =============================================================================
-- 日期维一次性装载（dwd.dim_date）：多年数据，幂等写入。
--
-- ClickHouse 没有递归 CTE 的日历生成函数，这里用 numbers() + 日期运算生成；
-- 重复执行时 ReplacingMergeTree 会因 ORDER BY dt 去重，不会产生重复行。
-- 变量：
--   ${start_date} 起始日期（含），例如 2010-01-01
--   ${years}      生成年数
-- =============================================================================

INSERT INTO dwd.dim_date
SELECT
    toDate(number)                                              AS dt,
    toYear(toDate(number))                                      AS year,
    toQuarter(toDate(number))                                   AS quarter,
    toMonth(toDate(number))                                     AS month,
    toDayOfMonth(toDate(number))                                AS day,
    toISOWeek(toDate(number))                                   AS week_of_year,
    toDayOfWeek(toDate(number))                                 AS day_of_week,
    if(toDayOfWeek(toDate(number)) IN (6, 7), 1, 0)             AS is_weekend
FROM numbers(toUInt32(dateDiff('day', toDate('${start_date}'),
        addYears(toDate('${start_date}'), toUInt32('${years}')))))
;
