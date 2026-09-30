package com.tmdbwh.offline;

import static org.apache.spark.sql.functions.col;
import static org.apache.spark.sql.functions.lit;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.functions;
import org.apache.spark.sql.expressions.Window;
import org.apache.spark.sql.expressions.WindowSpec;

/**
 * DWD → DWS：主题域汇总。
 *
 * <p>设计原则：
 *
 * <ul>
 *   <li>只依赖 DWD 的维度与事实，不再回读原始 JSON——保证"口径变更只需改 DWD"；
 *   <li>所有指标都显式命名（如 popularity_wow），避免在报表里重复写窗口函数；
 *   <li>按 (dt, 维度) 唯一，重跑同一天覆盖写入即可，不需要幂等去重逻辑。
 * </ul>
 */
public final class DwsTransform {

    private DwsTransform() {}

    /** 电影每日指标（不含预算 / ROI）。 */
    public static Dataset<Row> movieMetric1d(Dataset<Row> factSnapshot, Dataset<Row> factCredit, String dt) {
        return movieMetric1d(factSnapshot, factCredit, null, dt);
    }

    /**
     * 电影每日指标：热度环比、演职员规模、预算与 ROI。
     *
     * @param dimMovieCurrent 电影维度的当前有效版本（提供 budget）；为 null 时 budget / roi 置空
     */
    public static Dataset<Row> movieMetric1d(Dataset<Row> factSnapshot, Dataset<Row> factCredit,
            Dataset<Row> dimMovieCurrent, String dt) {
        // 注意用 rangeBetween 而不是 rowsBetween：缺少某些天的快照时，按行数偏移会取到错误的日期，
        // 只有按日期范围（7 天）定位才能保证"环比"真的是 7 天前
        WindowSpec prev = Window.partitionBy("movie_id")
                .orderBy(functions.col("dt").cast("timestamp").cast("long"))
                .rangeBetween(-7 * 86400, -7 * 86400);
        Dataset<Row> credits = factCredit
                .groupBy("dt", "movie_id")
                .agg(functions.sum(functions.when(col("credit_type").equalTo("cast"), lit(1)).otherwise(lit(0)))
                                .as("cast_count"),
                        functions.sum(functions.when(col("credit_type").equalTo("crew"), lit(1)).otherwise(lit(0)))
                                .as("crew_count"));

        Dataset<Row> withPrev = factSnapshot
                .withColumn("popularity_7d_ago", functions.lag("popularity", 7).over(prev));

        Dataset<Row> base = withPrev
                .join(credits, ScalaSeqs.of("dt", "movie_id"), "left")
                .select(
                        col("dt"),
                        col("movie_id"),
                        col("title"),
                        Columns.yearOf(col("release_date")).as("release_year"),
                        col("popularity"),
                        Columns.changeRatio(col("popularity"), col("popularity_7d_ago")).as("popularity_wow"),
                        col("vote_average"),
                        col("vote_count"),
                        col("revenue"),
                        functions.coalesce(col("cast_count").cast("long"), lit(0L)).as("cast_count"),
                        functions.coalesce(col("crew_count").cast("long"), lit(0L)).as("crew_count"))
                .withColumn("load_time", lit(DwdTransform.loadTimeOf(dt)).cast("timestamp"));

        if (dimMovieCurrent == null) {
            return base
                    .withColumn("budget", lit(null).cast("long"))
                    .withColumn("roi", lit(null).cast("double"))
                    .select("dt", "movie_id", "title", "release_year", "popularity", "popularity_wow",
                            "vote_average", "vote_count", "revenue", "budget", "roi", "cast_count", "crew_count",
                            "load_time");
        }
        // 预算来自维度的当前有效版本（SCD2）：只选 movie_id / budget，避免 join 后列名歧义
        Dataset<Row> budget = dimMovieCurrent
                .filter(col("valid_to").cast("timestamp").equalTo(Columns.farFuture()))
                .select(col("movie_id"), col("budget"))
                .distinct();
        return base
                .join(budget, ScalaSeqs.of("movie_id"), "left")
                .withColumn("roi", Columns.safeDivide(col("revenue"), col("budget")))
                .select("dt", "movie_id", "title", "release_year", "popularity", "popularity_wow", "vote_average",
                        "vote_count", "revenue", "budget", "roi", "cast_count", "crew_count", "load_time");
    }

    /** 类型 × 上映年份汇总。 */
    public static Dataset<Row> genreYearMetric(Dataset<Row> bridgeGenre, Dataset<Row> dimMovieCurrent, String dt) {
        return bridgeGenre
                .join(dimMovieCurrent, ScalaSeqs.of("movie_id"), "inner")
                .groupBy(col("genre_id"), col("genre_name"), col("release_year").as("release_year"))
                .agg(functions.countDistinct(col("movie_id")).as("movie_count"),
                        functions.avg(col("popularity")).as("avg_popularity"),
                        functions.avg(col("vote_average")).as("avg_vote_average"),
                        functions.sum(col("revenue")).as("total_revenue"))
                .filter(col("release_year").isNotNull())
                .withColumn("dt", lit(dt).cast("date"))
                .withColumn("load_time", lit(DwdTransform.loadTimeOf(dt)).cast("timestamp"))
                .select("dt", "genre_id", "genre_name", "release_year", "movie_count", "avg_popularity",
                        "avg_vote_average", "total_revenue", "load_time");
    }

    /** 人物生涯汇总。 */
    public static Dataset<Row> personCareer(Dataset<Row> factCredit, Dataset<Row> dimPersonCurrent,
            Dataset<Row> dimMovieCurrent, String dt) {
        Dataset<Row> works = factCredit
                .join(dimMovieCurrent.select("movie_id", "release_year", "vote_average"),
                        ScalaSeqs.of("movie_id"), "inner");
        return works
                .groupBy("person_id")
                .agg(functions.countDistinct(col("movie_id")).as("work_count"),
                        functions.sum(col("is_director")).as("as_director_count"),
                        functions.sum(functions.when(col("credit_type").equalTo("cast"), lit(1)).otherwise(lit(0)))
                                .as("as_cast_count"),
                        functions.min(col("release_year")).as("first_work_year"),
                        functions.max(col("release_year")).as("latest_work_year"),
                        functions.avg(col("vote_average")).as("avg_vote_average"))
                .join(dimPersonCurrent.select("person_id", "person_name", "known_for_department", "popularity"),
                        ScalaSeqs.of("person_id"), "left")
                .withColumn("dt", lit(dt).cast("date"))
                .withColumn("load_time", lit(DwdTransform.loadTimeOf(dt)).cast("timestamp"))
                .select("dt", "person_id", "person_name", "known_for_department", "work_count", "as_director_count",
                        "as_cast_count", "first_work_year", "latest_work_year", "avg_vote_average", "popularity",
                        "load_time");
    }

    /** 国家 × 年份汇总。 */
    public static Dataset<Row> countryYearMetric(Dataset<Row> bridgeCountry, Dataset<Row> dimMovieCurrent, String dt) {
        return bridgeCountry
                .join(dimMovieCurrent, ScalaSeqs.of("movie_id"), "inner")
                .groupBy(col("country_code"), col("release_year"))
                .agg(functions.countDistinct(col("movie_id")).as("movie_count"),
                        functions.sum(col("revenue")).as("total_revenue"),
                        functions.avg(col("vote_average")).as("avg_vote_average"))
                .filter(col("release_year").isNotNull())
                .withColumn("dt", lit(dt).cast("date"))
                .withColumn("load_time", lit(DwdTransform.loadTimeOf(dt)).cast("timestamp"))
                .select("dt", "country_code", "release_year", "movie_count", "total_revenue", "avg_vote_average",
                        "load_time");
    }

    /** 公司财务汇总。 */
    public static Dataset<Row> companyFinance(Dataset<Row> bridgeCompany, Dataset<Row> dimMovieCurrent, String dt) {
        return bridgeCompany
                .join(dimMovieCurrent, ScalaSeqs.of("movie_id"), "inner")
                .groupBy(col("company_id"), col("company_name"))
                .agg(functions.countDistinct(col("movie_id")).as("movie_count"),
                        functions.sum(col("budget")).as("total_budget"),
                        functions.sum(col("revenue")).as("total_revenue"),
                        functions.avg(Columns.safeDivide(col("revenue"), col("budget"))).as("avg_roi"),
                        functions.avg(col("vote_average")).as("avg_vote_average"))
                .withColumn("dt", lit(dt).cast("date"))
                .withColumn("load_time", lit(DwdTransform.loadTimeOf(dt)).cast("timestamp"))
                .select("dt", "company_id", "company_name", "movie_count", "total_budget", "total_revenue",
                        "avg_roi", "avg_vote_average", "load_time");
    }
}
