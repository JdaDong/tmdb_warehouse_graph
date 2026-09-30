package com.tmdbwh.offline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.apache.spark.sql.functions.col;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** ADS 报表口径。 */
class AdsTransformTest {

    private static SparkSession spark;

    @BeforeAll
    static void setUp() {
        spark = SparkSession.builder()
                .master("local[1]").appName("ads-test")
                .config("spark.ui.enabled", "false")
                .config("spark.sql.shuffle.partitions", "1")
                .config("spark.sql.session.timeZone", "UTC")
                .getOrCreate();
    }

    @AfterAll
    static void tearDown() {
        if (spark != null) {
            spark.stop();
        }
    }

    private static final StructType METRIC = new StructType()
            .add("dt", DataTypes.DateType)
            .add("movie_id", DataTypes.LongType)
            .add("title", DataTypes.StringType)
            .add("release_year", DataTypes.IntegerType)
            .add("popularity", DataTypes.DoubleType)
            .add("popularity_wow", DataTypes.DoubleType)
            .add("vote_average", DataTypes.DoubleType)
            .add("vote_count", DataTypes.IntegerType)
            .add("revenue", DataTypes.LongType)
            .add("budget", DataTypes.LongType)
            .add("roi", DataTypes.DoubleType)
            .add("cast_count", DataTypes.LongType)
            .add("crew_count", DataTypes.LongType)
            .add("load_time", DataTypes.TimestampType);

    private Dataset<Row> metrics() {
        return spark.createDataFrame(java.util.Arrays.asList(
                        RowFactory.create(java.sql.Date.valueOf("2026-09-30"), 1L, "A", 2010, 100.0, 0.5, 8.0, 100,
                                2000L, 500L, 4.0, 10L, 20L, java.sql.Timestamp.valueOf("2026-09-30 00:00:00")),
                        RowFactory.create(java.sql.Date.valueOf("2026-09-30"), 2L, "B", 2011, 90.0, 0.1, 7.5, 90,
                                900L, 300L, 3.0, 8L, 15L, java.sql.Timestamp.valueOf("2026-09-30 00:00:00")),
                        // 预算未知（0）-> 不参与 ROI 排行
                        RowFactory.create(java.sql.Date.valueOf("2026-09-30"), 3L, "C", 2012, 80.0, 0.0, 7.0, 80,
                                800L, 0L, null, 5L, 10L, java.sql.Timestamp.valueOf("2026-09-30 00:00:00"))),
                METRIC);
    }

    @Test
    void topMovieRanksByPopularity() {
        Dataset<Row> top = AdsTransform.topMovie(metrics(), "2026-09-30", 10);

        assertThat(top.count()).isEqualTo(3);
        assertThat(SparkTestSupport.values(top, "rank")).containsExactly(1, 2, 3);
        assertThat(SparkTestSupport.values(top, "movie_id")).containsExactly(1L, 2L, 3L);
        // 结果按名次排序，接口直接查即可
        assertThat(SparkTestSupport.first(top, "title")).isEqualTo("A");
    }

    @Test
    void topMovieHonoursLimit() {
        assertThat(AdsTransform.topMovie(metrics(), "2026-09-30", 2).count()).isEqualTo(2);
    }

    @Test
    void roiRankingExcludesUnknownBudget() {
        Dataset<Row> ranking = AdsTransform.roiRanking(metrics(), "2026-09-30", 10);

        // 只有 A(4.0) 与 B(3.0) 的 ROI 已知；C 的预算为 0（未知）必须排除
        assertThat(ranking.count()).isEqualTo(2);
        assertThat(SparkTestSupport.values(ranking, "movie_id")).containsExactly(1L, 2L);
    }

    @Test
    void genreTrendAggregatesByGenre() {
        Dataset<Row> bridge = spark.createDataFrame(java.util.Arrays.asList(
                        RowFactory.create(1L, 28L, "Action"),
                        RowFactory.create(2L, 28L, "Action"),
                        RowFactory.create(3L, 18L, "Drama")),
                new StructType()
                        .add("movie_id", DataTypes.LongType)
                        .add("genre_id", DataTypes.LongType)
                        .add("genre_name", DataTypes.StringType));

        Dataset<Row> trend = AdsTransform.genreTrend(metrics(), bridge, "2026-09-30");

        assertThat(trend.count()).isEqualTo(2);
        Dataset<Row> action = trend.filter(col("genre_id").equalTo(28L));
        assertThat(SparkTestSupport.first(action, "movie_count")).isEqualTo(2L);
        assertThat(SparkTestSupport.first(action, "avg_popularity")).isEqualTo(95.0);
    }

    @Test
    void personInfluenceRanksByCompositeScore() {
        Dataset<Row> career = spark.createDataFrame(java.util.Arrays.asList(
                        RowFactory.create(java.sql.Date.valueOf("2026-09-30"), 5L, "Nolan", "Directing", 5L, 5L, 0L,
                                2005, 2026, 8.4, 30.0),
                        RowFactory.create(java.sql.Date.valueOf("2026-09-30"), 9L, "Leo", "Acting", 30L, 0L, 30L,
                                1990, 2026, 7.5, 50.0)),
                new StructType()
                        .add("dt", DataTypes.DateType)
                        .add("person_id", DataTypes.LongType)
                        .add("person_name", DataTypes.StringType)
                        .add("known_for_department", DataTypes.StringType)
                        .add("work_count", DataTypes.LongType)
                        .add("as_director_count", DataTypes.LongType)
                        .add("as_cast_count", DataTypes.LongType)
                        .add("first_work_year", DataTypes.IntegerType)
                        .add("latest_work_year", DataTypes.IntegerType)
                        .add("avg_vote_average", DataTypes.DoubleType)
                        .add("popularity", DataTypes.DoubleType));

        Dataset<Row> influence = AdsTransform.personInfluence(career, "2026-09-30", 10);

        assertThat(influence.count()).isEqualTo(2);
        assertThat(SparkTestSupport.values(influence, "rank")).containsExactly(1, 2);
        Row first = influence.head();
        // Leo：作品 30 部 × 2 + 评分 7.5 × 10 + 热度 50 = 185 > Nolan 的 5×2+84+30=124
        assertThat(first.getLong(first.fieldIndex("person_id"))).isEqualTo(9L);
        assertThat(first.getDouble(first.fieldIndex("influence_score"))).isEqualTo(185.0);
    }
}
