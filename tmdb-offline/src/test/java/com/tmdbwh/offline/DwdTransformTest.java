package com.tmdbwh.offline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** DWD 清洗与建模：用真实 Spark 跑真实 TMDB 报文。 */
class DwdTransformTest {

    @TempDir
    static java.nio.file.Path warehouse;

    private static SparkSession spark;

    @BeforeAll
    static void setUp() {
        spark = SparkTestSupport.session(warehouse);
    }

    @AfterAll
    static void tearDown() {
        if (spark != null) {
            spark.stop();
        }
    }

    private Dataset<Row> movies(String dt) {
        String payloadA = SparkTestSupport.moviePayload(27205, "Inception", 83.95, 160000000L, 836000000L);
        String payloadB = SparkTestSupport.moviePayload(155, "The Dark Knight", 105.69, 185000000L, 1006000000L);
        return DwdTransform.parseMovie(SparkTestSupport.raw(spark, "movie", dt, payloadA, payloadB));
    }

    @Test
    void parsesPayloadAndCleansZeroBudgetAsNull() {
        Dataset<Row> dim = DwdTransform.dimMovie(movies("2026-09-30"), "2026-09-30");

        assertThat(dim.count()).isEqualTo(2);
        List<Object> budgets = SparkTestSupport.values(
                dim.filter(org.apache.spark.sql.functions.col("movie_id").equalTo(27205L)), "budget");
        assertThat(budgets.get(0)).isEqualTo(160000000L);
        // budget=0 在 TMDB 表示"未录入"，必须清洗为 NULL，否则会拉偏均值与 ROI
        Dataset<Row> zero = DwdTransform.dimMovie(
                DwdTransform.parseMovie(SparkTestSupport.raw(spark, "movie", "2026-09-30",
                        SparkTestSupport.moviePayload(603, "The Matrix", 60.0, 0L, 0L))), "2026-09-30");
        assertThat(SparkTestSupport.first(zero, "budget")).isNull();
        assertThat(SparkTestSupport.first(zero, "revenue")).isNull();
    }

    @Test
    void derivesReleaseYearAndAdultFlag() {
        Dataset<Row> dim = DwdTransform.dimMovie(movies("2026-09-30"), "2026-09-30");
        assertThat(SparkTestSupport.first(dim, "release_year")).isEqualTo(2010);
        assertThat(SparkTestSupport.first(dim, "adult")).isEqualTo(0);
        assertThat(SparkTestSupport.first(dim, "collection_id")).isEqualTo(263L);
    }

    @Test
    void surrogateKeyIsStableAndDistinct() {
        Dataset<Row> first = DwdTransform.dimMovie(movies("2026-09-30"), "2026-09-30");
        Dataset<Row> second = DwdTransform.dimMovie(movies("2026-09-30"), "2026-09-30");
        // 同一业务日期重跑：代理键必须完全一致（下游关联才不会断）
        assertThat(SparkTestSupport.values(first, "movie_sk"))
                .containsExactlyElementsOf(SparkTestSupport.values(second, "movie_sk"));
        assertThat(new java.util.HashSet<>(SparkTestSupport.values(first, "movie_sk"))).hasSize(2);
    }

    @Test
    void creditsFactSplitsCastAndCrewAndMarksDirector() {
        Dataset<Row> fact = DwdTransform.factMovieCredit(movies("2026-09-30"), "2026-09-30");

        // 每部电影 2 cast + 2 crew
        assertThat(fact.count()).isEqualTo(8);
        assertThat(fact.filter(org.apache.spark.sql.functions.col("credit_type").equalTo("cast")).count())
                .isEqualTo(4);
        assertThat(fact.filter(org.apache.spark.sql.functions.col("is_director").equalTo(1)).count())
                .isEqualTo(2);
        // 同一人（Nolan）担任导演与编剧，credit_id 不同 -> 保留两行
        assertThat(fact.filter(org.apache.spark.sql.functions.col("person_id").equalTo(525L)).count())
                .isEqualTo(4);
    }

    @Test
    void releaseFactExplodesByCountryAndMapsType() {
        Dataset<Row> fact = DwdTransform.factMovieRelease(movies("2026-09-30"), "2026-09-30");

        assertThat(fact.count()).isEqualTo(4);
        assertThat(new java.util.HashSet<>(SparkTestSupport.values(fact, "country_code")))
                .containsExactly("US", "GB");
        assertThat(new java.util.HashSet<>(SparkTestSupport.values(fact, "release_type"))).containsExactly("院线");
        assertThat(new java.util.HashSet<>(SparkTestSupport.values(fact, "certification")))
                .containsExactly("PG-13", "12A");
    }

    @Test
    void bridgesAndDictionariesAreBuilt() {
        Dataset<Row> parsed = movies("2026-09-30");
        Dataset<Row> tv = DwdTransform.parseTv(SparkTestSupport.raw(spark, "tv", "2026-09-30",
                SparkTestSupport.tvPayload(1399, "Game of Thrones", 346.0)));

        assertThat(DwdTransform.bridgeMovieGenre(parsed, "2026-09-30").count()).isEqualTo(4);
        assertThat(DwdTransform.bridgeMovieCompany(parsed, "2026-09-30").count()).isEqualTo(4);
        assertThat(DwdTransform.bridgeMovieCountry(parsed, "2026-09-30").count()).isEqualTo(4);
        // 电影与剧集的 genre ID 空间不同，用 media_type 区分
        assertThat(DwdTransform.dimGenre(parsed, tv, "2026-09-30").count()).isEqualTo(3);
        assertThat(DwdTransform.dimCompany(parsed, "2026-09-30").count()).isEqualTo(2);
        assertThat(DwdTransform.dimKeyword(parsed, "2026-09-30").count()).isEqualTo(2);
        assertThat(DwdTransform.dimCountry(parsed, "2026-09-30").count()).isEqualTo(2);
        assertThat(DwdTransform.dimLanguage(parsed, "2026-09-30").count()).isEqualTo(1);
    }

    @Test
    void companyNameIsNormalizedByRemovingParenthesizedSuffix() {
        Dataset<Row> parsed = DwdTransform.parseMovie(SparkTestSupport.raw(spark, "movie", "2026-09-30",
                SparkTestSupport.moviePayload(27205, "Inception", 10.0, 100L, 200L).replace(
                        "\"name\":\"Legendary Pictures\"", "\"name\":\"Legendary Pictures (US)\"")));
        Dataset<Row> dim = DwdTransform.dimCompany(parsed, "2026-09-30");
        assertThat(SparkTestSupport.values(dim, "name_normalized")).contains("Legendary Pictures");
    }

    @Test
    void personDimensionMapsGenderCode() {
        Dataset<Row> parsed = DwdTransform.parsePerson(SparkTestSupport.raw(spark, "person", "2026-09-30",
                SparkTestSupport.personPayload(525, "Christopher Nolan", 30.0)));
        Dataset<Row> dim = DwdTransform.dimPerson(parsed, "2026-09-30");

        assertThat(SparkTestSupport.first(dim, "gender")).isEqualTo("male");
        assertThat(SparkTestSupport.first(dim, "known_for_department")).isEqualTo("Directing");
        assertThat(SparkTestSupport.first(dim, "birthday")).isNotNull();
    }

    @Test
    void dailySnapshotIsUniquePerMovieAndDay() {
        Dataset<Row> twice = SparkTestSupport.raw(spark, "movie", "2026-09-30",
                SparkTestSupport.moviePayload(27205, "Inception", 20.0, 100L, 200L),
                SparkTestSupport.moviePayload(27205, "Inception", 21.0, 100L, 200L));
        Dataset<Row> fact = DwdTransform.factMovieDailySnapshot(DwdTransform.parseMovie(twice), "2026-09-30");
        // 同一电影同一天重复采集 -> 只保留一行（丢弃较早的那条）
        assertThat(fact.count()).isEqualTo(1);
    }

    @Test
    void loadTimeIsBusinessDateMidnightSoRerunsMatch() {
        assertThat(DwdTransform.loadTimeOf("2026-09-30"))
                .isEqualTo(java.time.LocalDate.of(2026, 9, 30).atStartOfDay(java.time.ZoneOffset.UTC)
                        .toInstant().toEpochMilli());
        assertThat(DwdTransform.loadTimeOf("2026-09-30")).isEqualTo(DwdTransform.loadTimeOf("2026-09-30"));
    }
}
