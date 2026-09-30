package com.tmdbwh.offline;

import org.apache.spark.sql.Column;
import org.apache.spark.sql.functions;

/**
 * 复用的列表达式。
 *
 * <p>集中在两处使用：Spark 离线作业的列派生，以及需要翻译成 ClickHouse SQL 的等价逻辑。
 * 同名方法放在一起，便于两边口径对齐（改一处时提醒检查另一处）。
 */
public final class Columns {

    private Columns() {}

    /**
     * 把 TMDB 的 "0" 视为未知：budget=0 / revenue=0 在 TMDB 中表示"未录入"，
     * 参与平均值与 ROI 计算会把结果拉向 0，因此统一清洗为 NULL。
     */
    public static Column zeroAsNull(Column column) {
        return functions.when(column.equalTo(0L), functions.lit(null)).otherwise(column);
    }

    /** 解析 TMDB 日期字符串（yyyy-MM-dd），空串与非法值返回 null。 */
    public static Column parseDate(Column column) {
        return functions.to_date(column, "yyyy-MM-dd");
    }

    /** 从日期取年份（清洗后的可空列）。 */
    public static Column yearOf(Column dateColumn) {
        return functions.year(dateColumn);
    }

    /** 是否导演（crew.job = Director）。 */
    public static Column isDirector(Column job) {
        return functions.when(functions.lower(job).equalTo("director"), functions.lit(1))
                .otherwise(functions.lit(0));
    }

    /** 千禧年哨兵日期：SCD2 中 valid_to 的"未失效"标记。 */
    public static Column farFuture() {
        return functions.lit("2099-12-31 00:00:00").cast("timestamp");
    }

    /** 布尔转 UInt8（ClickHouse 无 boolean 存储类型，写入前统一转换）。 */
    public static Column booleanToUInt8(Column column) {
        return functions.when(column, functions.lit(1)).otherwise(functions.lit(0));
    }

    /** 安全除法：分母为 0 或 NULL 时返回 NULL，避免除零异常污染整批数据。 */
    public static Column safeDivide(Column numerator, Column denominator) {
        return functions.when(denominator.isNull().or(denominator.equalTo(0)), functions.lit(null))
                .otherwise(numerator.divide(denominator));
    }

    /** 环比变化率：(本期 - 上期) / 上期，上期缺失或为 0 时返回 0。 */
    public static Column changeRatio(Column current, Column previous) {
        Column base = functions.when(previous.isNull().or(previous.equalTo(0)), functions.lit(null))
                .otherwise(previous);
        return functions.coalesce(current.minus(base).divide(base), functions.lit(0.0));
    }
}
