package com.tmdbwh.offline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.apache.spark.sql.functions.col;

import java.util.Arrays;
import java.util.List;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** SCD2 合并语义。 */
class Scd2Test {

    private static SparkSession spark;

    @BeforeAll
    static void setUp() {
        spark = SparkSession.builder()
                .master("local[1]").appName("scd2-test")
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

    private static final StructType SCHEMA = new StructType()
            .add("movie_id", DataTypes.LongType)
            .add("title", DataTypes.StringType)
            .add("popularity", DataTypes.DoubleType)
            .add("valid_from", DataTypes.TimestampType)
            .add("valid_to", DataTypes.TimestampType);

    private static Dataset<Row> rows(Object[]... data) {
        List<Row> list = Arrays.stream(data).map(RowFactory::create).collect(java.util.stream.Collectors.toList());
        return spark.createDataFrame(list, SCHEMA);
    }

    private static java.sql.Timestamp ts(String text) {
        return java.sql.Timestamp.valueOf(text);
    }

    private static final List<String> BUSINESS = Arrays.asList("title", "popularity");

    @Test
    void firstRunCreatesVersionsWithSentinelEndDate() {
        Dataset<Row> incoming = rows(new Object[] {1L, "Inception", 10.0, ts("2026-09-30 00:00:00"), null});

        Dataset<Row> merged = Scd2.merge(null, incoming, "movie_id", BUSINESS);

        assertThat(merged.count()).isEqualTo(1);
        Row row = merged.head();
        assertThat(row.getTimestamp(row.fieldIndex("valid_to"))).isEqualTo(ts("2099-12-31 00:00:00"));
    }

    @Test
    void unchangedContentDoesNotCreateNewVersion() {
        Dataset<Row> existing = rows(new Object[] {1L, "Inception", 10.0, ts("2026-09-29 00:00:00"),
                ts("2099-12-31 00:00:00")});
        Dataset<Row> incoming = rows(new Object[] {1L, "Inception", 10.0, ts("2026-09-30 00:00:00"), null});

        Dataset<Row> merged = Scd2.merge(existing, incoming, "movie_id", BUSINESS);

        // 内容没变 -> 不新增版本（否则每天为几十万部电影各写一行）
        assertThat(merged.count()).isEqualTo(1);
        assertThat(SparkTestSupport.first(merged, "valid_from")).isEqualTo(ts("2026-09-29 00:00:00"));
    }

    @Test
    void changedContentClosesOldVersionAndOpensNewOne() {
        Dataset<Row> existing = rows(new Object[] {1L, "Inception", 10.0, ts("2026-09-29 00:00:00"),
                ts("2099-12-31 00:00:00")});
        Dataset<Row> incoming = rows(new Object[] {1L, "Inception", 88.0, ts("2026-09-30 00:00:00"), null});

        Dataset<Row> merged = Scd2.merge(existing, incoming, "movie_id", BUSINESS);

        assertThat(merged.count()).isEqualTo(2);
        Dataset<Row> closed = merged.filter(col("valid_from").equalTo(ts("2026-09-29 00:00:00")));
        Dataset<Row> opened = merged.filter(col("valid_from").equalTo(ts("2026-09-30 00:00:00")));
        // 旧版本收敛到新版本的生效时间：区间首尾相接，不重叠不留空洞
        assertThat(SparkTestSupport.first(closed, "valid_to")).isEqualTo(ts("2026-09-30 00:00:00"));
        assertThat(SparkTestSupport.first(opened, "valid_to")).isEqualTo(ts("2099-12-31 00:00:00"));
        assertThat(SparkTestSupport.first(opened, "popularity")).isEqualTo(88.0);
    }

    @Test
    void newEntityIsAddedAlongsideExistingOnes() {
        Dataset<Row> existing = rows(new Object[] {1L, "Inception", 10.0, ts("2026-09-29 00:00:00"),
                ts("2099-12-31 00:00:00")});
        Dataset<Row> incoming = rows(
                new Object[] {2L, "The Dark Knight", 105.0, ts("2026-09-30 00:00:00"), null});

        Dataset<Row> merged = Scd2.merge(existing, incoming, "movie_id", BUSINESS);

        assertThat(merged.count()).isEqualTo(2);
        assertThat(merged.filter(col("movie_id").equalTo(1L)).count()).isEqualTo(1);
        assertThat(merged.filter(col("movie_id").equalTo(2L)).count()).isEqualTo(1);
    }

    @Test
    void mergeIsIdempotent() {
        Dataset<Row> existing = rows(new Object[] {1L, "Inception", 10.0, ts("2026-09-29 00:00:00"),
                ts("2099-12-31 00:00:00")});
        Dataset<Row> incoming = rows(new Object[] {1L, "Inception", 88.0, ts("2026-09-30 00:00:00"), null});

        Dataset<Row> once = Scd2.merge(existing, incoming, "movie_id", BUSINESS);
        Dataset<Row> twice = Scd2.merge(once, incoming, "movie_id", BUSINESS);

        // 同一业务日期重复执行：已收敛的旧版本不会被再次放宽，也不会多出版本
        assertThat(twice.count()).isEqualTo(once.count());
        assertThat(SparkTestSupport.values(twice, "valid_from"))
                .containsExactlyElementsOf(SparkTestSupport.values(once, "valid_from"));
    }

    @Test
    void hashIsStableForSameContentAndDiffersWhenContentDiffers() {
        Dataset<Row> a = rows(new Object[] {1L, "Inception", 10.0, ts("2026-09-30 00:00:00"), null});
        Dataset<Row> b = rows(new Object[] {1L, "Inception", 10.0, ts("2026-09-30 00:00:00"), null});
        Dataset<Row> c = rows(new Object[] {1L, "Inception", 11.0, ts("2026-09-30 00:00:00"), null});

        assertThat(SparkTestSupport.first(a.withColumn("h", Scd2.hashOf(a, BUSINESS)), "h"))
                .isEqualTo(SparkTestSupport.first(b.withColumn("h", Scd2.hashOf(b, BUSINESS)), "h"));
        assertThat(SparkTestSupport.first(a.withColumn("h", Scd2.hashOf(a, BUSINESS)), "h"))
                .isNotEqualTo(SparkTestSupport.first(c.withColumn("h", Scd2.hashOf(c, BUSINESS)), "h"));
    }

    @Test
    void nullsAreTreatedAsEmptyStringInHash() {
        Dataset<Row> withNull = rows(new Object[] {1L, null, 10.0, ts("2026-09-30 00:00:00"), null});
        Dataset<Row> withEmpty = rows(new Object[] {1L, "", 10.0, ts("2026-09-30 00:00:00"), null});
        assertThat(SparkTestSupport.first(withNull.withColumn("h", Scd2.hashOf(withNull, BUSINESS)), "h"))
                .isEqualTo(SparkTestSupport.first(withEmpty.withColumn("h", Scd2.hashOf(withEmpty, BUSINESS)), "h"));
    }

    @Test
    void rejectsEmptyBusinessColumns() {
        Dataset<Row> incoming = rows(new Object[] {1L, "Inception", 10.0, ts("2026-09-30 00:00:00"), null});
        assertThatThrownBy(() -> Scd2.merge(null, incoming, "movie_id", Arrays.asList()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
