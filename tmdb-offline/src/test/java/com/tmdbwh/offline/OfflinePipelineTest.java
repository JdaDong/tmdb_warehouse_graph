package com.tmdbwh.offline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 端到端：贴源 → DWD → DWS → ADS → 写入湖仓（本地 Iceberg + 临时目录真跑）。
 *
 * <p>覆盖集成测试才能验证的部分：各层表真的被写出来、维度走全量覆盖、事实走分区覆盖、
 * 以及重复执行结果一致。
 */
class OfflinePipelineTest {

    @TempDir
    static java.nio.file.Path warehouse;

    private static SparkSession spark;

    @BeforeAll
    static void setUp() {
        spark = SparkTestSupport.session(warehouse);
        spark.sql("CREATE DATABASE IF NOT EXISTS lake.dwd");
        spark.sql("CREATE DATABASE IF NOT EXISTS lake.dws");
        spark.sql("CREATE DATABASE IF NOT EXISTS lake.ads");
    }

    @AfterAll
    static void tearDown() {
        if (spark != null) {
            spark.stop();
        }
    }

    private static Dataset<Row> rawMovie(String dt) {
        return SparkTestSupport.raw(spark, "movie", dt,
                SparkTestSupport.moviePayload(27205, "Inception", 83.95, 160000000L, 836000000L),
                SparkTestSupport.moviePayload(155, "The Dark Knight", 105.69, 185000000L, 1006000000L));
    }

    @Test
    void transformProducesEveryLayer() {
        Map<String, Dataset<Row>> layers = OfflinePipeline.transform(spark, rawMovie("2026-09-30"),
                SparkTestSupport.raw(spark, "tv", "2026-09-30",
                        SparkTestSupport.tvPayload(1399, "Game of Thrones", 346.0)),
                SparkTestSupport.raw(spark, "person", "2026-09-30",
                        SparkTestSupport.personPayload(525, "Christopher Nolan", 30.0)),
                "2026-09-30");

        assertThat(layers).containsKeys(
                "dwd.dim_movie", "dwd.dim_person", "dwd.dim_genre", "dwd.dim_company",
                "dwd.fact_movie_credit", "dwd.fact_movie_daily_snapshot", "dwd.fact_movie_release",
                "dws.dws_movie_metric_1d", "dws.dws_person_career",
                "ads.ads_top_movie", "ads.ads_roi_ranking");
        // 两部电影 -> 每日指标两行；榜单包含它们
        assertThat(layers.get("dws.dws_movie_metric_1d").count()).isEqualTo(2);
        assertThat(layers.get("ads.ads_top_movie").count()).isEqualTo(2);
        // 预算均已知 -> ROI 排行两行
        assertThat(layers.get("ads.ads_roi_ranking").count()).isEqualTo(2);
    }

    @Test
    void emptySourceStillProducesEmptyLayers() {
        Map<String, Dataset<Row>> layers = OfflinePipeline.transform(spark,
                SparkTestSupport.raw(spark, "movie", "2026-10-01"),
                SparkTestSupport.raw(spark, "tv", "2026-10-01"),
                SparkTestSupport.raw(spark, "person", "2026-10-01"),
                "2026-10-01");

        // "今天没有变更" 不应让链路失败：各层为空但结构完整
        assertThat(layers.get("dwd.dim_movie")).isNotNull();
        assertThat(layers.get("dwd.dim_movie").count()).isZero();
        assertThat(layers.get("dws.dws_movie_metric_1d").count()).isZero();
    }

    @Test
    void dimensionsHaveClosedVersionChainAndFactsHaveDt() {
        Map<String, Dataset<Row>> layers = OfflinePipeline.transform(spark, rawMovie("2026-09-30"),
                SparkTestSupport.raw(spark, "tv", "2026-09-30"),
                SparkTestSupport.raw(spark, "person", "2026-09-30",
                        SparkTestSupport.personPayload(525, "Christopher Nolan", 30.0)),
                "2026-09-30");

        Dataset<Row> dimMovie = layers.get("dwd.dim_movie");
        assertThat(SparkTestSupport.columns(dimMovie)).contains("valid_from", "valid_to");
        assertThat(dimMovie.filter(org.apache.spark.sql.functions.col("valid_to")
                .equalTo(org.apache.spark.sql.functions.lit("2099-12-31 00:00:00").cast("timestamp"))).count())
                .isEqualTo(2);

        Dataset<Row> fact = layers.get("dwd.fact_movie_credit");
        assertThat(SparkTestSupport.columns(fact)).contains("dt");
        assertThat(SparkTestSupport.first(fact, "dt")).isEqualTo(java.sql.Date.valueOf("2026-09-30"));
    }

    @Test
    void writesToLakeAndIsIdempotentOnRerun() {
        OfflinePipeline pipeline = new OfflinePipeline(
                com.tmdbwh.common.config.AppConfig.from(
                        com.typesafe.config.ConfigFactory.parseString(
                                "tmdbwh.iceberg.warehouse = \"" + warehouse.toUri() + "\"")
                                .withFallback(com.typesafe.config.ConfigFactory.defaultReference())),
                java.time.LocalDate.of(2026, 9, 30), "test-run", spark, true);

        Map<String, Dataset<Row>> layers = OfflinePipeline.transform(spark, rawMovie("2026-09-30"),
                SparkTestSupport.raw(spark, "tv", "2026-09-30",
                        SparkTestSupport.tvPayload(1399, "Game of Thrones", 346.0)),
                SparkTestSupport.raw(spark, "person", "2026-09-30",
                        SparkTestSupport.personPayload(525, "Christopher Nolan", 30.0)),
                "2026-09-30");
        layers.forEach((table, dataset) -> {
            String[] parts = table.split("\\.");
            pipeline.writeLayer(parts[0], parts[1], dataset);
        });

        // 重跑同一天：分区覆盖后行数不变（否则每次重跑都会翻倍）
        layers.forEach((table, dataset) -> {
            String[] parts = table.split("\\.");
            pipeline.writeLayer(parts[0], parts[1], dataset);
        });
        long second = spark.read().format("iceberg").load("lake.dwd.fact_movie_credit").count();
        assertThat(second).isEqualTo(layers.get("dwd.fact_movie_credit").count());
        assertThat(second).isPositive();
    }

    @Test
    void dateDimCoversRequestedYears() {
        OfflinePipeline pipeline = new OfflinePipeline(
                com.tmdbwh.common.config.AppConfig.from(
                        com.typesafe.config.ConfigFactory.defaultReference()),
                java.time.LocalDate.of(2026, 9, 30), "test-run", spark, true);

        Dataset<Row> dim = pipeline.generateDateDim(2026, 2);

        assertThat(dim.count()).isEqualTo(730); // 2026、2027 均为平年
        assertThat(SparkTestSupport.columns(dim)).containsExactly("dt", "year", "quarter", "month", "day",
                "week_of_year", "day_of_week", "is_weekend");
        assertThat(SparkTestSupport.first(dim, "dt")).isEqualTo(java.sql.Date.valueOf("2026-01-01"));
    }
}
