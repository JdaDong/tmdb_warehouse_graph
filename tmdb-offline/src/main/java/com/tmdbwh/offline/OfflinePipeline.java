package com.tmdbwh.offline;

import static org.apache.spark.sql.functions.col;

import com.tmdbwh.common.clickhouse.ClickHouseClient;
import com.tmdbwh.common.config.AppConfig;
import com.tmdbwh.common.model.EntityType;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.functions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 离线主链路：ODS → DWD → DWS → ADS，写入湖仓（Iceberg），可选同步到 ClickHouse。
 *
 * <p>执行顺序（每一步都可单独跳过，便于排障与补数）：
 *
 * <ol>
 *   <li>读取贴源层当日分区（缺失时按空处理，"今天没有变更"不算失败）；
 *   <li>DWD：解析 JSON → 清洗 → 建模（维度走 SCD2 合并，事实按 dt 去重）；
 *   <li>DWS / ADS：聚合；
 *   <li>写入湖仓：维度全量覆盖、其余按 dt 分区覆盖（重跑结果一致）；
 *   <li>同步 ClickHouse：分区替换（维度用按窗口删除 + 重写）。
 * </ol>
 *
 * <p>幂等性来自两点：load_time 固定为业务日期零点（同一天重跑得到相同值），
 * 以及所有写入都是"覆盖"而不是"追加"。
 */
public class OfflinePipeline implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(OfflinePipeline.class);

    private final AppConfig config;
    private final OfflineContext context;
    private final SparkSession spark;
    private final boolean owned;

    /**
     * @param config 平台配置
     * @param businessDate 业务日期
     * @param runId 运行 ID（用于暂存表隔离）；为空时按业务日期生成
     * @param local 是否本地模式（测试用）
     */
    public OfflinePipeline(AppConfig config, LocalDate businessDate, String runId, boolean local) {
        this(config, businessDate, runId, null, local);
    }

    /** 复用已有 SparkSession（测试或调度内嵌场景）。 */
    public OfflinePipeline(AppConfig config, LocalDate businessDate, String runId, SparkSession spark, boolean local) {
        this.config = Objects.requireNonNull(config, "config");
        this.context = new OfflineContext(config, businessDate, runId);
        this.owned = spark == null;
        this.spark = spark != null ? spark : SparkSupport.build(config, "tmdbwh-offline", local);
    }

    public OfflineContext context() {
        return context;
    }

    public SparkSession spark() {
        return spark;
    }

    /**
     * 执行全链路。
     *
     * @param sync 是否同步到 ClickHouse
     * @return 每层产出的表名 → 行数
     */
    public Map<String, Long> run(boolean sync) {
        String dt = context.dt();
        LOG.info("离线链路开始 {}", context);

        String warehouse = OdsReader.warehouseOf(spark);
        Dataset<Row> rawMovie = OdsReader.read(spark, EntityType.MOVIE, dt, warehouse);
        Dataset<Row> rawTv = OdsReader.read(spark, EntityType.TV, dt, warehouse);
        Dataset<Row> rawPerson = OdsReader.read(spark, EntityType.PERSON, dt, warehouse);
        LOG.info("贴源读取完成: movie={} tv={} person={}", rawMovie.count(), rawTv.count(), rawPerson.count());

        Map<String, Dataset<Row>> layers = transform(spark, rawMovie, rawTv, rawPerson, dt);

        Map<String, Long> counts = new LinkedHashMap<>();
        layers.forEach((table, dataset) -> {
            String[] parts = table.split("\\.");
            writeLayer(parts[0], parts[1], dataset);
            counts.put(table, dataset.count());
        });
        LOG.info("湖仓写入完成: {}", counts);

        if (sync) {
            syncToClickHouse(counts.keySet());
        }
        return counts;
    }

    /**
     * 纯计算的分层转换（不依赖外部存储，便于单元测试）。
     *
     * @param rawMovie 电影贴源数据（可为不含数据的空 Dataset）
     * @param rawTv 剧集贴源数据
     * @param rawPerson 人物贴源数据
     * @return 表名 → Dataset
     */
    public static Map<String, Dataset<Row>> transform(SparkSession spark, Dataset<Row> rawMovie,
            Dataset<Row> rawTv, Dataset<Row> rawPerson, String dt) {
        Dataset<Row> movieParsed = DwdTransform.parseMovie(rawMovie);
        Dataset<Row> tvParsed = DwdTransform.parseTv(rawTv);
        Dataset<Row> personParsed = DwdTransform.parsePerson(rawPerson);

        Map<String, Dataset<Row>> layers = new LinkedHashMap<>();

        // ---------- DWD 维度（SCD2） ----------
        Dataset<Row> dimMovieCurrent = LakeTables.readOrNull(spark, "dwd", "dim_movie");
        Dataset<Row> dimMovie;
        if (dimMovieCurrent == null) {
            dimMovie = addValidTo(DwdTransform.dimMovie(movieParsed, dt));
        } else {
            dimMovie = Scd2.merge(dimMovieCurrent, DwdTransform.dimMovie(movieParsed, dt), "movie_id",
                    DwdTransform.MOVIE_BUSINESS_COLUMNS);
        }
        layers.put("dwd.dim_movie", dimMovie);

        Dataset<Row> personExisting = LakeTables.readOrNull(spark, "dwd", "dim_person");
        Dataset<Row> dimPerson = personExisting == null
                ? addValidTo(DwdTransform.dimPerson(personParsed, dt))
                : Scd2.merge(personExisting, DwdTransform.dimPerson(personParsed, dt), "person_id",
                        DwdTransform.PERSON_BUSINESS_COLUMNS);
        layers.put("dwd.dim_person", dimPerson);

        // ---------- DWD 全量维度与桥接 ----------
        layers.put("dwd.dim_genre", DwdTransform.dimGenre(movieParsed, tvParsed, dt));
        layers.put("dwd.dim_company", DwdTransform.dimCompany(movieParsed, dt));
        layers.put("dwd.dim_keyword", DwdTransform.dimKeyword(movieParsed, dt));
        layers.put("dwd.dim_country", DwdTransform.dimCountry(movieParsed, dt));
        layers.put("dwd.dim_language", DwdTransform.dimLanguage(movieParsed, dt));
        layers.put("dwd.bridge_movie_genre", DwdTransform.bridgeMovieGenre(movieParsed, dt));
        layers.put("dwd.bridge_movie_company", DwdTransform.bridgeMovieCompany(movieParsed, dt));
        layers.put("dwd.bridge_movie_country", DwdTransform.bridgeMovieCountry(movieParsed, dt));

        // ---------- DWD 事实 ----------
        Dataset<Row> factCredit = DwdTransform.factMovieCredit(movieParsed, dt);
        Dataset<Row> factSnapshot = DwdTransform.factMovieDailySnapshot(movieParsed, dt);
        Dataset<Row> factRelease = DwdTransform.factMovieRelease(movieParsed, dt);
        layers.put("dwd.fact_movie_credit", factCredit);
        layers.put("dwd.fact_movie_daily_snapshot", factSnapshot);
        layers.put("dwd.fact_movie_release", factRelease);
        layers.put("dwd.fact_change_event", DwdTransform.factChangeEvent(rawMovie, dt));

        // ---------- DWS ----------
        Dataset<Row> movieMetric = DwsTransform.movieMetric1d(factSnapshot, factCredit, currentDim(dimMovie), dt);
        layers.put("dws.dws_movie_metric_1d", movieMetric);
        layers.put("dws.dws_genre_year_metric",
                DwsTransform.genreYearMetric(layers.get("dwd.bridge_movie_genre"), currentDim(dimMovie), dt));
        layers.put("dws.dws_person_career",
                DwsTransform.personCareer(factCredit, currentPerson(dimPerson), currentDim(dimMovie), dt));
        layers.put("dws.dws_country_year_metric",
                DwsTransform.countryYearMetric(layers.get("dwd.bridge_movie_country"), currentDim(dimMovie), dt));
        layers.put("dws.dws_company_finance",
                DwsTransform.companyFinance(layers.get("dwd.bridge_movie_company"), currentDim(dimMovie), dt));

        // ---------- ADS ----------
        layers.put("ads.ads_top_movie", AdsTransform.topMovie(movieMetric, dt, 100));
        layers.put("ads.ads_genre_trend", AdsTransform.genreTrend(movieMetric, layers.get("dwd.bridge_movie_genre"), dt));
        layers.put("ads.ads_roi_ranking", AdsTransform.roiRanking(movieMetric, dt, 100));
        layers.put("ads.ads_person_influence",
                AdsTransform.personInfluence(layers.get("dws.dws_person_career"), dt, 100));
        return layers;
    }

    /** 维度走全量覆盖（版本链必须整体一致），其余按 dt 分区覆盖。 */
    void writeLayer(String schema, String table, Dataset<Row> dataset) {
        boolean isDimension = table.startsWith("dim_");
        if (isDimension) {
            LakeTables.writeOverwrite(dataset, schema, table);
        } else if (hasColumn(dataset, "dt")) {
            LakeTables.writePartition(spark, dataset, schema, table);
        } else {
            LOG.warn("表 {} 既不是维度也没有 dt 列，按全量覆盖处理", table);
            LakeTables.writeOverwrite(dataset, schema, table);
        }
    }

    /** 生成并执行 ClickHouse 同步：维度按窗口删除后重写，其余按分区替换。 */
    void syncToClickHouse(Iterable<String> tables) {
        OfflineSqlRunner runner = new OfflineSqlRunner(config.getClickhouse().getCluster());
        try (ClickHouseClient client = ClickHouseClient.create(config.getClickhouse())) {
            for (String table : tables) {
                String[] parts = table.split("\\.");
                String schema = parts[0];
                String name = parts[1];
                List<String> sql;
                if (name.startsWith("dim_")) {
                    sql = new java.util.ArrayList<>(runner.deleteByDate(schema, name, "valid_from", context.dt()));
                    sql.add(runner.insertFromSource(schema, name, context.dt()));
                } else {
                    sql = runner.replacePartition(schema, name, context.dt(), context.stagingSuffix(),
                            "CREATE TABLE IF NOT EXISTS " + schema + "." + name + context.stagingSuffix()
                                    + com.tmdbwh.common.clickhouse.ClickHouseSql.onCluster(
                                            config.getClickhouse().getCluster())
                                    + " AS " + schema + "." + name);
                }
                for (String statement : sql) {
                    LOG.info("同步 ClickHouse: {}", statement);
                    client.execute(statement);
                }
            }
        }
    }

    /** 生成日期维（一次性装载，多年数据）。 */
    public Dataset<Row> generateDateDim(int startYear, int years) {
        LocalDate start = LocalDate.of(startYear, 1, 1);
        int days = (int) java.time.temporal.ChronoUnit.DAYS.between(start, start.plusYears(years));
        // 用 timestampadd 而不是 date_add：后者在 Spark 3.0+ 要求第二个参数是 Column，直接传数字会不匹配
        return spark.range(0, days)
                .withColumn("dt", functions.expr(
                        "timestampadd(DAY, id, timestamp('" + start + " 00:00:00'))").cast("date"))
                .withColumn("year", functions.year(col("dt")))
                .withColumn("quarter", functions.quarter(col("dt")))
                .withColumn("month", functions.month(col("dt")))
                .withColumn("day", functions.dayofmonth(col("dt")))
                .withColumn("week_of_year", functions.weekofyear(col("dt")))
                .withColumn("day_of_week", functions.dayofweek(col("dt")))
                .withColumn("is_weekend", functions.when(functions.dayofweek(col("dt")).isin(1, 7), functions.lit(1))
                        .otherwise(functions.lit(0)))
                .drop("id");
    }

    /** 首次构建维度时补上"未失效"标记列。 */
    private static Dataset<Row> addValidTo(Dataset<Row> versions) {
        return versions.withColumn("valid_to", Columns.farFuture());
    }

    /** 当前有效版本的电影维度（供 DWS 取预算与上映年份）。 */
    /**
     * 当前有效版本的电影维度（供 DWS 取预算、上映年份、热度与票房）。
     *
     * <p>刻意<b>不包含 title</b>：DWS 的多张汇总表自身已有 title 列，
     * join 后会因同名列产生歧义（Spark 会报 Ambiguous reference）。
     */
    static Dataset<Row> currentDim(Dataset<Row> dimMovie) {
        return dimMovie
                .filter(col("valid_to").cast("timestamp").equalTo(Columns.farFuture()))
                .select("movie_id", "release_year", "popularity", "vote_average", "revenue", "budget");
    }

    /** 当前有效版本的人物维度。 */
    static Dataset<Row> currentPerson(Dataset<Row> dimPerson) {
        return dimPerson
                .filter(col("valid_to").cast("timestamp").equalTo(Columns.farFuture()))
                .select(col("person_id"), col("name").as("person_name"), col("known_for_department"),
                        col("popularity"));
    }

    static boolean hasColumn(Dataset<Row> dataset, String column) {
        for (String c : dataset.columns()) {
            if (c.equals(column)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() {
        if (owned) {
            spark.stop();
        }
    }
}
