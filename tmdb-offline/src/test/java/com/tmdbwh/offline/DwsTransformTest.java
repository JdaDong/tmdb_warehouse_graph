package com.tmdbwh.offline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.apache.spark.sql.functions.col;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** DWS 汇总口径。 */
class DwsTransformTest {

    private static SparkSession spark;

    @BeforeAll
    static void setUp() {
        spark = SparkSession.builder()
                .master("local[1]").appName("dws-test")
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

    /**
     * 构造快照事实：同一部电影连续 8 天（09-23 ~ 09-30）。
     *
     * <p>lag(7) 按行偏移，因此必须提供<b>连续</b>的每日快照；这也说明调用方应把历史快照一并传入，
     * 而不是只传当天数据（见 DwsTransform 的注释）。
     */
    private Dataset<Row> snapshots() {
        java.util.List<org.apache.spark.sql.Row> rows = new java.util.ArrayList<>();
        java.time.LocalDate start = java.time.LocalDate.of(2026, 9, 23);
        double[] popularity = {10.0, 11.0, 12.0, 13.0, 14.0, 15.0, 16.0, 20.0};
        for (int i = 0; i < popularity.length; i++) {
            rows.add(org.apache.spark.sql.RowFactory.create(java.sql.Date.valueOf(start.plusDays(i)), 1L, "A",
                    popularity[i], 8.0, 100, 1000L, java.sql.Date.valueOf("2010-07-16")));
        }
        return spark.createDataFrame(rows,
                new org.apache.spark.sql.types.StructType()
                        .add("dt", org.apache.spark.sql.types.DataTypes.DateType)
                        .add("movie_id", org.apache.spark.sql.types.DataTypes.LongType)
                        .add("title", org.apache.spark.sql.types.DataTypes.StringType)
                        .add("popularity", org.apache.spark.sql.types.DataTypes.DoubleType)
                        .add("vote_average", org.apache.spark.sql.types.DataTypes.DoubleType)
                        .add("vote_count", org.apache.spark.sql.types.DataTypes.IntegerType)
                        .add("revenue", org.apache.spark.sql.types.DataTypes.LongType)
                        .add("release_date", org.apache.spark.sql.types.DataTypes.DateType));
    }

    private Dataset<Row> credits() {
        return spark.createDataFrame(java.util.Arrays.asList(
                        org.apache.spark.sql.RowFactory.create(java.sql.Date.valueOf("2026-09-30"), "c1", 1L, 9L,
                                "cast", "", "", "", 0, 0),
                        org.apache.spark.sql.RowFactory.create(java.sql.Date.valueOf("2026-09-30"), "c2", 1L, 9L,
                                "cast", "", "", "", 1, 0),
                        org.apache.spark.sql.RowFactory.create(java.sql.Date.valueOf("2026-09-30"), "c3", 1L, 5L,
                                "crew", "Directing", "Director", "", null, 1),
                        org.apache.spark.sql.RowFactory.create(java.sql.Date.valueOf("2026-09-23"), "c0", 1L, 9L,
                                "cast", "", "", "", 0, 0)),
                new org.apache.spark.sql.types.StructType()
                        .add("dt", org.apache.spark.sql.types.DataTypes.DateType)
                        .add("credit_id", org.apache.spark.sql.types.DataTypes.StringType)
                        .add("movie_id", org.apache.spark.sql.types.DataTypes.LongType)
                        .add("person_id", org.apache.spark.sql.types.DataTypes.LongType)
                        .add("credit_type", org.apache.spark.sql.types.DataTypes.StringType)
                        .add("department", org.apache.spark.sql.types.DataTypes.StringType)
                        .add("job", org.apache.spark.sql.types.DataTypes.StringType)
                        .add("character_name", org.apache.spark.sql.types.DataTypes.StringType)
                        .add("cast_order", org.apache.spark.sql.types.DataTypes.IntegerType)
                        .add("is_director", org.apache.spark.sql.types.DataTypes.IntegerType));
    }

    @Test
    void movieMetricComputesWowAndCreditCounts() {
        Dataset<Row> metric = DwsTransform.movieMetric1d(snapshots(), credits(), "2026-09-30");

        Dataset<Row> today = metric.filter(col("dt").equalTo(java.sql.Date.valueOf("2026-09-30")));
        assertThat(today.count()).isEqualTo(1);
        Row row = today.head();
        assertThat(row.getDouble(row.fieldIndex("popularity"))).isEqualTo(20.0);
        // 7 天前是 10.0 -> 环比 +100%
        assertThat(row.getDouble(row.fieldIndex("popularity_wow"))).isEqualTo(1.0);
        assertThat(row.getLong(row.fieldIndex("cast_count"))).isEqualTo(2L);
        assertThat(row.getLong(row.fieldIndex("crew_count"))).isEqualTo(1L);
        assertThat(row.getLong(row.fieldIndex("revenue"))).isEqualTo(1000L);
        // 未提供维度时预算与 ROI 为空，而不是 0
        assertThat(row.isNullAt(row.fieldIndex("budget"))).isTrue();
    }

    @Test
    void firstDayHasZeroWowInsteadOfNull() {
        Dataset<Row> metric = DwsTransform.movieMetric1d(snapshots(), credits(), "2026-09-30");
        Row row = metric.filter(col("dt").equalTo(java.sql.Date.valueOf("2026-09-23"))).head();
        // 没有历史数据时环比记 0，避免报表出现空值
        assertThat(row.getDouble(row.fieldIndex("popularity_wow"))).isEqualTo(0.0);
    }

    @Test
    void budgetAndRoiComeFromCurrentDimensionVersion() {
        Dataset<Row> dim = spark.createDataFrame(java.util.Arrays.asList(
                        org.apache.spark.sql.RowFactory.create(1L, 2010, 20.0, 8.1, 1000L, 500L,
                                java.sql.Timestamp.valueOf("2026-09-30 00:00:00"),
                                java.sql.Timestamp.valueOf("2099-12-31 00:00:00"))),
                new org.apache.spark.sql.types.StructType()
                        .add("movie_id", org.apache.spark.sql.types.DataTypes.LongType)
                        .add("release_year", org.apache.spark.sql.types.DataTypes.IntegerType)
                        .add("popularity", org.apache.spark.sql.types.DataTypes.DoubleType)
                        .add("vote_average", org.apache.spark.sql.types.DataTypes.DoubleType)
                        .add("revenue", org.apache.spark.sql.types.DataTypes.LongType)
                        .add("budget", org.apache.spark.sql.types.DataTypes.LongType)
                        .add("valid_from", org.apache.spark.sql.types.DataTypes.TimestampType)
                        .add("valid_to", org.apache.spark.sql.types.DataTypes.TimestampType));

        Dataset<Row> metric = DwsTransform.movieMetric1d(snapshots(), credits(), dim, "2026-09-30");
        Row row = metric.filter(col("dt").equalTo(java.sql.Date.valueOf("2026-09-30"))).head();
        assertThat(row.getLong(row.fieldIndex("budget"))).isEqualTo(500L);
        assertThat(row.getDouble(row.fieldIndex("roi"))).isEqualTo(2.0);
    }

    @Test
    void roiIsNullWhenBudgetIsZero() {
        Dataset<Row> dim = spark.createDataFrame(java.util.Arrays.asList(
                        org.apache.spark.sql.RowFactory.create(1L, 2010, 20.0, 8.1, 1000L, 0L,
                                java.sql.Timestamp.valueOf("2026-09-30 00:00:00"),
                                java.sql.Timestamp.valueOf("2099-12-31 00:00:00"))),
                new org.apache.spark.sql.types.StructType()
                        .add("movie_id", org.apache.spark.sql.types.DataTypes.LongType)
                        .add("release_year", org.apache.spark.sql.types.DataTypes.IntegerType)
                        .add("popularity", org.apache.spark.sql.types.DataTypes.DoubleType)
                        .add("vote_average", org.apache.spark.sql.types.DataTypes.DoubleType)
                        .add("revenue", org.apache.spark.sql.types.DataTypes.LongType)
                        .add("budget", org.apache.spark.sql.types.DataTypes.LongType)
                        .add("valid_from", org.apache.spark.sql.types.DataTypes.TimestampType)
                        .add("valid_to", org.apache.spark.sql.types.DataTypes.TimestampType));

        Dataset<Row> metric = DwsTransform.movieMetric1d(snapshots(), credits(), dim, "2026-09-30");
        // 预算为 0（TMDB 表示未知）时 ROI 必须是 NULL，而不是 Infinity
        assertThat(metric.filter(col("roi").isNotNull()).count()).isZero();
    }

    @Test
    void genreYearAndCountryAggregations() {
        Dataset<Row> dim = spark.createDataFrame(java.util.Arrays.asList(
                        org.apache.spark.sql.RowFactory.create(1L, 2010, 20.0, 8.1, 1000L, 500L,
                                java.sql.Timestamp.valueOf("2026-09-30 00:00:00"),
                                java.sql.Timestamp.valueOf("2099-12-31 00:00:00"))),
                new org.apache.spark.sql.types.StructType()
                        .add("movie_id", org.apache.spark.sql.types.DataTypes.LongType)
                        .add("release_year", org.apache.spark.sql.types.DataTypes.IntegerType)
                        .add("popularity", org.apache.spark.sql.types.DataTypes.DoubleType)
                        .add("vote_average", org.apache.spark.sql.types.DataTypes.DoubleType)
                        .add("revenue", org.apache.spark.sql.types.DataTypes.LongType)
                        .add("budget", org.apache.spark.sql.types.DataTypes.LongType)
                        .add("valid_from", org.apache.spark.sql.types.DataTypes.TimestampType)
                        .add("valid_to", org.apache.spark.sql.types.DataTypes.TimestampType));
        Dataset<Row> bridge = spark.createDataFrame(java.util.Arrays.asList(
                        org.apache.spark.sql.RowFactory.create(1L, 28L, "Action"),
                        org.apache.spark.sql.RowFactory.create(1L, 878L, "Science Fiction")),
                new org.apache.spark.sql.types.StructType()
                        .add("movie_id", org.apache.spark.sql.types.DataTypes.LongType)
                        .add("genre_id", org.apache.spark.sql.types.DataTypes.LongType)
                        .add("genre_name", org.apache.spark.sql.types.DataTypes.StringType));

        Dataset<Row> genreYear = DwsTransform.genreYearMetric(bridge, dim, "2026-09-30");
        assertThat(genreYear.count()).isEqualTo(2);
        assertThat(SparkTestSupport.first(genreYear, "movie_count")).isEqualTo(1L);
        assertThat(SparkTestSupport.first(genreYear, "avg_popularity")).isEqualTo(20.0);
    }

    @Test
    void personCareerAggregatesWorksAndDirectorCount() {
        Dataset<Row> movieDim = spark.createDataFrame(java.util.Arrays.asList(
                        org.apache.spark.sql.RowFactory.create(1L, 2010, 8.1)),
                new org.apache.spark.sql.types.StructType()
                        .add("movie_id", org.apache.spark.sql.types.DataTypes.LongType)
                        .add("release_year", org.apache.spark.sql.types.DataTypes.IntegerType)
                        .add("vote_average", org.apache.spark.sql.types.DataTypes.DoubleType));
        Dataset<Row> personDim = spark.createDataFrame(java.util.Arrays.asList(
                        org.apache.spark.sql.RowFactory.create(5L, "Nolan", "Directing", 30.0)),
                new org.apache.spark.sql.types.StructType()
                        .add("person_id", org.apache.spark.sql.types.DataTypes.LongType)
                        .add("person_name", org.apache.spark.sql.types.DataTypes.StringType)
                        .add("known_for_department", org.apache.spark.sql.types.DataTypes.StringType)
                        .add("popularity", org.apache.spark.sql.types.DataTypes.DoubleType));

        Dataset<Row> career = DwsTransform.personCareer(credits(), personDim, movieDim, "2026-09-30");
        Dataset<Row> director = career.filter(col("person_id").equalTo(5L));
        assertThat(director.count()).isEqualTo(1);
        Row row = director.head();
        assertThat(row.getLong(row.fieldIndex("work_count"))).isEqualTo(1L);
        assertThat(row.getLong(row.fieldIndex("as_director_count"))).isEqualTo(1L);
        assertThat(row.getString(row.fieldIndex("person_name"))).isEqualTo("Nolan");
    }
}
