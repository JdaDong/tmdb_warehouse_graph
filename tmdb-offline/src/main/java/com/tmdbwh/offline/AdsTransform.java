package com.tmdbwh.offline;

import static org.apache.spark.sql.functions.col;
import static org.apache.spark.sql.functions.lit;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.functions;
import org.apache.spark.sql.expressions.Window;
import org.apache.spark.sql.expressions.WindowSpec;

/**
 * DWS → ADS：面向报表与接口的最终结果。
 *
 * <p>特点：字段按消费方需求裁剪、排序与排名在此固化，下游只做简单查询；
 * 每张表都按 dt 分区，重跑覆盖即可，无需去重。
 */
public final class AdsTransform {

    private AdsTransform() {}

    /** 热门电影榜：按热度排名。 */
    public static Dataset<Row> topMovie(Dataset<Row> movieMetric, String dt, int topN) {
        WindowSpec rank = Window.partitionBy("dt").orderBy(col("popularity").desc());
        return movieMetric
                .withColumn("rank", functions.dense_rank().over(rank))
                .filter(col("rank").leq(topN))
                .select(col("dt"), col("rank").cast("int").as("rank"), col("movie_id"), col("title"),
                        col("release_year"), col("popularity"), col("vote_average"), col("vote_count"),
                        col("revenue"), col("roi"))
                .withColumn("load_time", lit(DwdTransform.loadTimeOf(dt)).cast("timestamp"))
                .sort(col("rank"));
    }

    /** 类型趋势：热度与评分，含周环比。 */
    public static Dataset<Row> genreTrend(Dataset<Row> movieMetric, Dataset<Row> bridgeGenre, String dt) {
        WindowSpec prev = Window.partitionBy("genre_id").orderBy("dt").rowsBetween(-7, -7);
        Dataset<Row> byGenre = bridgeGenre
                .join(movieMetric.select("dt", "movie_id", "popularity", "vote_average"),
                        ScalaSeqs.of("movie_id"), "inner")
                .groupBy("dt", "genre_id", "genre_name")
                .agg(functions.countDistinct(col("movie_id")).as("movie_count"),
                        functions.avg(col("popularity")).as("avg_popularity"),
                        functions.avg(col("vote_average")).as("avg_vote_average"))
                .withColumn("popularity_7d_ago", functions.lag("avg_popularity", 7).over(prev))
                .withColumn("popularity_wow", Columns.changeRatio(col("avg_popularity"), col("popularity_7d_ago")))
                .withColumn("load_time", lit(DwdTransform.loadTimeOf(dt)).cast("timestamp"))
                .select("dt", "genre_id", "genre_name", "movie_count", "avg_popularity", "avg_vote_average",
                        "popularity_wow", "load_time");
        return byGenre;
    }

    /** ROI 排行：预算与票房都已知才参与排名（避免 0 值污染）。 */
    public static Dataset<Row> roiRanking(Dataset<Row> movieMetric, String dt, int topN) {
        WindowSpec rank = Window.partitionBy("dt").orderBy(col("roi").desc());
        return movieMetric
                .filter(col("roi").isNotNull())
                .withColumn("rank", functions.dense_rank().over(rank))
                .filter(col("rank").leq(topN))
                .select(col("dt"), col("rank").cast("int").as("rank"), col("movie_id"), col("title"),
                        col("budget"), col("revenue"), col("roi"), col("vote_average"))
                .withColumn("load_time", lit(DwdTransform.loadTimeOf(dt)).cast("timestamp"))
                .sort(col("rank"));
    }

    /** 人物影响力：作品数量与口碑的综合分（简单加权，口径集中在此便于调整）。 */
    public static Dataset<Row> personInfluence(Dataset<Row> personCareer, String dt, int topN) {
        WindowSpec rank = Window.partitionBy("dt").orderBy(col("influence_score").desc());
        return personCareer
                .withColumn("influence_score",
                        functions.coalesce(col("popularity"), lit(0.0))
                                .plus(functions.coalesce(col("work_count").cast("double"), lit(0.0)).multiply(2.0))
                                .plus(functions.coalesce(col("avg_vote_average"), lit(0.0)).multiply(10.0)))
                .withColumn("rank", functions.dense_rank().over(rank))
                .filter(col("rank").leq(topN))
                .select(col("dt"), col("rank").cast("int").as("rank"), col("person_id"), col("person_name"),
                        col("known_for_department"), col("influence_score"), col("work_count"),
                        col("avg_vote_average"))
                .withColumn("pagerank", lit(0.0))
                .withColumn("load_time", lit(DwdTransform.loadTimeOf(dt)).cast("timestamp"))
                .select("dt", "rank", "person_id", "person_name", "known_for_department", "influence_score",
                        "pagerank", "work_count", "avg_vote_average", "load_time")
                .sort(col("rank"));
    }
}
