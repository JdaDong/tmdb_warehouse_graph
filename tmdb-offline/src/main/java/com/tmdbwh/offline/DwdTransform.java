package com.tmdbwh.offline;

import static org.apache.spark.sql.functions.col;
import static org.apache.spark.sql.functions.explode;
import static org.apache.spark.sql.functions.explode_outer;
import static org.apache.spark.sql.functions.from_json;
import static org.apache.spark.sql.functions.lit;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.functions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ODS → DWD：清洗、规范化、维度建模。
 *
 * <p>职责边界：
 *
 * <ul>
 *   <li>本类只做"结构化 + 清洗 + 建模"，不做业务聚合（聚合属于 DWS）；
 *   <li>输出列与目标表（ClickHouse / Iceberg）严格对齐，写入前不做隐式类型推断；
 *   <li><b>清洗规则集中在此</b>：例如 TMDB 用 0 表示"预算/票房未知"，这里统一转为 NULL，
 *       否则均值与 ROI 会被大量 0 拉偏——这类规则散落在各作业里必然会出现口径不一致。
 * </ul>
 */
public final class DwdTransform {

    private static final Logger LOG = LoggerFactory.getLogger(DwdTransform.class);

    private DwdTransform() {}

    /** 参与电影维度变化检测的业务列（不含 overview：文本噪声大且极少变化）。 */
    public static final List<String> MOVIE_BUSINESS_COLUMNS = Arrays.asList(
            "title", "status", "release_date", "runtime", "budget", "revenue",
            "popularity", "vote_average", "vote_count", "adult", "collection_id");

    /** 参与人物维度变化检测的业务列。 */
    public static final List<String> PERSON_BUSINESS_COLUMNS = Arrays.asList(
            "name", "gender", "birthday", "deathday", "place_of_birth", "known_for_department", "popularity", "adult");

    /** 业务日期对应的 epoch 毫秒（UTC 零点），作为 load_time，保证同一天重跑结果一致。 */
    public static long loadTimeOf(String dt) {
        return LocalDate.parse(dt).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    /** 维度代理键：自然键 + 版本生效时间，保证同一版本的键稳定。 */
    public static Column surrogateKey(Column naturalKey, Column validFrom) {
        return functions.abs(functions.hash(functions.concat_ws(":", naturalKey.cast("string"),
                validFrom.cast("string")))).cast("long");
    }

    // ============================== 解析 ==============================

    /** 解析电影原始报文。 */
    public static Dataset<Row> parseMovie(Dataset<Row> raw) {
        return raw.withColumn("p", from_json(col("payload"), Schemas.movie()))
                .filter(col("p.id").isNotNull())
                .select(col("entity_id"), col("dt"), col("ingest_time"), col("p").as("p"));
    }

    /** 解析剧集原始报文。 */
    public static Dataset<Row> parseTv(Dataset<Row> raw) {
        return raw.withColumn("p", from_json(col("payload"), Schemas.tv()))
                .filter(col("p.id").isNotNull())
                .select(col("entity_id"), col("dt"), col("ingest_time"), col("p").as("p"));
    }

    /** 解析人物原始报文。 */
    public static Dataset<Row> parsePerson(Dataset<Row> raw) {
        return raw.withColumn("p", from_json(col("payload"), Schemas.person()))
                .filter(col("p.id").isNotNull())
                .select(col("entity_id"), col("dt"), col("ingest_time"), col("p").as("p"));
    }

    // ============================== 维度 ==============================

    /** 电影维度（SCD2 的新版本）。 */
    public static Dataset<Row> dimMovie(Dataset<Row> parsed, String dt) {
        Dataset<Row> base = parsed
                .select(
                        col("p.id").as("movie_id"),
                        col("p.title").as("title"),
                        col("p.original_title").as("original_title"),
                        col("p.original_language").as("original_language"),
                        col("p.overview").as("overview"),
                        col("p.status").as("status"),
                        Columns.parseDate(col("p.release_date")).as("release_date"),
                        Columns.zeroAsNull(col("p.budget")).as("budget"),
                        Columns.zeroAsNull(col("p.revenue")).as("revenue"),
                        col("p.popularity").as("popularity"),
                        col("p.vote_average").as("vote_average"),
                        col("p.vote_count").as("vote_count"),
                        Columns.booleanToUInt8(col("p.adult")).as("adult"),
                        col("p.belongs_to_collection.id").as("collection_id"),
                        col("p.runtime").as("runtime"))
                .withColumn("release_year", Columns.yearOf(col("release_date")))
                .withColumn("valid_from", lit(dt + " 00:00:00").cast("timestamp"))
                .withColumn("load_time", lit(loadTimeOf(dt)).cast("timestamp"));
        return base
                .withColumn("movie_sk", surrogateKey(col("movie_id"), col("valid_from")))
                .select("movie_sk", "movie_id", "title", "original_title", "original_language", "overview",
                        "status", "release_date", "release_year", "runtime", "budget", "revenue", "popularity",
                        "vote_average", "vote_count", "adult", "collection_id", "valid_from", "load_time");
    }

    /** 人物维度（SCD2 的新版本）。 */
    public static Dataset<Row> dimPerson(Dataset<Row> parsed, String dt) {
        Column gender = functions.when(col("p.gender").equalTo(1), lit("female"))
                .when(col("p.gender").equalTo(2), lit("male"))
                .when(col("p.gender").equalTo(3), lit("non-binary"))
                .otherwise(lit("unknown"));
        return parsed
                .select(
                        col("p.id").as("person_id"),
                        col("p.name").as("name"),
                        gender.as("gender"),
                        Columns.parseDate(col("p.birthday")).as("birthday"),
                        Columns.parseDate(col("p.deathday")).as("deathday"),
                        col("p.place_of_birth").as("place_of_birth"),
                        col("p.known_for_department").as("known_for_department"),
                        col("p.popularity").as("popularity"),
                        Columns.booleanToUInt8(col("p.adult")).as("adult"))
                .withColumn("valid_from", lit(dt + " 00:00:00").cast("timestamp"))
                .withColumn("load_time", lit(loadTimeOf(dt)).cast("timestamp"))
                .withColumn("person_sk", surrogateKey(col("person_id"), col("valid_from")))
                .select("person_sk", "person_id", "name", "gender", "birthday", "deathday", "place_of_birth",
                        "known_for_department", "popularity", "adult", "valid_from", "load_time");
    }

    /** 公司维度（全量，ReplacingMergeTree 覆盖即可）。 */
    public static Dataset<Row> dimCompany(Dataset<Row> parsed, String dt) {
        return parsed.select(explode(col("p.production_companies")).as("c"))
                .select(col("c.id").as("company_id"), col("c.name").as("name"))
                .filter(col("company_id").isNotNull())
                .withColumn("name_normalized", functions.regexp_replace(col("name"), "\\s*\\(.*?\\)\\s*$", ""))
                .withColumn("origin_country", lit(""))
                .withColumn("parent_company_id", lit(null).cast("long"))
                .withColumn("load_time", lit(loadTimeOf(dt)).cast("timestamp"))
                .dropDuplicates("company_id");
    }

    /** 类型维度：电影与剧集的 ID 空间不同，用 media_type 区分。 */
    public static Dataset<Row> dimGenre(Dataset<Row> movieParsed, Dataset<Row> tvParsed, String dt) {
        Dataset<Row> movie = movieParsed.select(explode(col("p.genres")).as("g"))
                .select(lit("movie").as("media_type"), col("g.id").as("genre_id"), col("g.name").as("genre_name"));
        Dataset<Row> tv = tvParsed.select(explode(col("p.genres")).as("g"))
                .select(lit("tv").as("media_type"), col("g.id").as("genre_id"), col("g.name").as("genre_name"));
        return movie.unionByName(tv)
                .filter(col("genre_id").isNotNull())
                .withColumn("load_time", lit(loadTimeOf(dt)).cast("timestamp"))
                .dropDuplicates("media_type", "genre_id");
    }

    /**
     * 关键词维度。
     *
     * <p>注意：TMDB 详情接口返回的关键词<b>只有 name 没有 id</b>（只有 keywords 列表接口才带 id），
     * 因此这里用名称的稳定哈希作为 surrogate id。若后续接入关键词列表接口，改为直接取 id 即可，
     * 下游 dim_keyword 的 ORDER BY keyword_id 不变。
     */
    public static Dataset<Row> dimKeyword(Dataset<Row> parsed, String dt) {
        return parsed.select(explode(col("p.keywords.keywords")).as("k"))
                .select(col("k.name").as("keyword_name"))
                .filter(col("keyword_name").isNotNull())
                .dropDuplicates("keyword_name")
                .withColumn("keyword_id", functions.abs(functions.hash(col("keyword_name"))).cast("long"))
                .withColumn("load_time", lit(loadTimeOf(dt)).cast("timestamp"))
                .select("keyword_id", "keyword_name", "load_time");
    }

    /** 国家 / 语言字典：详情接口只有编码没有本地名，名称先填编码，接入 configuration 接口后可补全。 */
    public static Dataset<Row> dimCountry(Dataset<Row> parsed, String dt) {
        return parsed.select(explode(col("p.production_countries")).as("c"))
                .select(col("c.iso_3166_1").as("country_code"))
                .filter(col("country_code").isNotNull())
                .dropDuplicates("country_code")
                .withColumn("country_name_en", col("country_code"))
                .withColumn("country_name_native", col("country_code"))
                .withColumn("load_time", lit(loadTimeOf(dt)).cast("timestamp"))
                .select("country_code", "country_name_en", "country_name_native", "load_time");
    }

    public static Dataset<Row> dimLanguage(Dataset<Row> parsed, String dt) {
        return parsed.select(explode(col("p.spoken_languages")).as("l"))
                .select(col("l.iso_639_1").as("language_code"))
                .filter(col("language_code").isNotNull())
                .dropDuplicates("language_code")
                .withColumn("language_name_en", col("language_code"))
                .withColumn("language_name_native", col("language_code"))
                .withColumn("load_time", lit(loadTimeOf(dt)).cast("timestamp"))
                .select("language_code", "language_name_en", "language_name_native", "load_time");
    }

    // ============================== 桥接表 ==============================

    public static Dataset<Row> bridgeMovieGenre(Dataset<Row> parsed, String dt) {
        return parsed.select(col("p.id").as("movie_id"), explode(col("p.genres")).as("g"))
                .select(col("movie_id"), col("g.id").as("genre_id"), col("g.name").as("genre_name"))
                .filter(col("movie_id").isNotNull().and(col("genre_id").isNotNull()))
                .withColumn("load_time", lit(loadTimeOf(dt)).cast("timestamp"))
                .dropDuplicates("movie_id", "genre_id");
    }

    public static Dataset<Row> bridgeMovieCompany(Dataset<Row> parsed, String dt) {
        return parsed.select(col("p.id").as("movie_id"), explode(col("p.production_companies")).as("c"))
                .select(col("movie_id"), col("c.id").as("company_id"), col("c.name").as("company_name"))
                .filter(col("movie_id").isNotNull().and(col("company_id").isNotNull()))
                .withColumn("load_time", lit(loadTimeOf(dt)).cast("timestamp"))
                .dropDuplicates("movie_id", "company_id");
    }

    public static Dataset<Row> bridgeMovieCountry(Dataset<Row> parsed, String dt) {
        return parsed.select(col("p.id").as("movie_id"), explode(col("p.production_countries")).as("c"))
                .select(col("movie_id"), col("c.iso_3166_1").as("country_code"))
                .filter(col("movie_id").isNotNull().and(col("country_code").isNotNull()))
                .withColumn("load_time", lit(loadTimeOf(dt)).cast("timestamp"))
                .dropDuplicates("movie_id", "country_code");
    }

    // ============================== 事实表 ==============================

    /**
     * 演职员事实：粒度为 credit_id。
     *
     * <p>同一人在同一部电影中可能担任多个职位（如导演兼编剧），因此不能用 (movie_id, person_id) 去重。
     */
    public static Dataset<Row> factMovieCredit(Dataset<Row> parsed, String dt) {
        Dataset<Row> cast = parsed.select(col("p.id").as("movie_id"), explode_outer(col("p.credits.cast")).as("m"))
                .select(col("movie_id"),
                        col("m.credit_id").as("credit_id"),
                        col("m.id").as("person_id"),
                        lit("cast").as("credit_type"),
                        lit("").as("department"),
                        lit("").as("job"),
                        col("m.character").as("character_name"),
                        col("m.order").as("cast_order"),
                        lit(0).as("is_director"));
        Dataset<Row> crew = parsed.select(col("p.id").as("movie_id"), explode_outer(col("p.credits.crew")).as("m"))
                .select(col("movie_id"),
                        col("m.credit_id").as("credit_id"),
                        col("m.id").as("person_id"),
                        lit("crew").as("credit_type"),
                        col("m.department").as("department"),
                        col("m.job").as("job"),
                        lit("").as("character_name"),
                        lit(null).cast("int").as("cast_order"),
                        Columns.isDirector(col("m.job")).as("is_director"));
        return cast.unionByName(crew)
                .filter(col("credit_id").isNotNull())
                .withColumn("dt", lit(dt).cast("date"))
                .withColumn("load_time", lit(loadTimeOf(dt)).cast("timestamp"))
                .select("dt", "credit_id", "movie_id", "person_id", "credit_type", "department", "job",
                        "character_name", "cast_order", "is_director", "load_time")
                .dropDuplicates("dt", "credit_id");
    }

    /** 电影每日快照事实：热度与评分随时间变化，按天保留一份快照。 */
    public static Dataset<Row> factMovieDailySnapshot(Dataset<Row> parsed, String dt) {
        return parsed.select(
                        col("p.id").as("movie_id"),
                        col("p.title").as("title"),
                        col("p.popularity").as("popularity"),
                        col("p.vote_average").as("vote_average"),
                        col("p.vote_count").as("vote_count"),
                        Columns.zeroAsNull(col("p.revenue")).as("revenue"),
                        Columns.parseDate(col("p.release_date")).as("release_date"))
                .withColumn("dt", lit(dt).cast("date"))
                .withColumn("load_time", lit(loadTimeOf(dt)).cast("timestamp"))
                .select("dt", "movie_id", "title", "popularity", "vote_average", "vote_count", "revenue",
                        "release_date", "load_time")
                .dropDuplicates("dt", "movie_id");
    }

    /** 分国家上映事实：一个电影 × 国家 × 上映日期 一行（同一国家可能有院线/数字等多种上映方式）。 */
    public static Dataset<Row> factMovieRelease(Dataset<Row> parsed, String dt) {
        return parsed.select(col("p.id").as("movie_id"),
                        explode_outer(col("p.release_dates.results")).as("r"))
                .select(col("movie_id"), col("r.iso_3166_1").as("country_code"),
                        explode_outer(col("r.release_dates")).as("d"))
                .select(col("movie_id"), col("country_code"),
                        col("d.type").as("release_type_raw"),
                        col("d.certification").as("certification"),
                        col("d.release_date").as("release_ts"))
                .filter(col("country_code").isNotNull())
                .withColumn("release_type",
                        functions.when(col("release_type_raw").equalTo(1), lit("首映"))
                                .when(col("release_type_raw").equalTo(2), lit("限定上映"))
                                .when(col("release_type_raw").equalTo(3), lit("院线"))
                                .when(col("release_type_raw").equalTo(4), lit("数字"))
                                .when(col("release_type_raw").equalTo(5), lit("实体"))
                                .when(col("release_type_raw").equalTo(6), lit("电视"))
                                .otherwise(lit("其他")))
                .withColumn("release_date", functions.to_timestamp(col("release_ts"), "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"))
                .withColumn("dt", lit(dt).cast("date"))
                .withColumn("load_time", lit(loadTimeOf(dt)).cast("timestamp"))
                .select("dt", "movie_id", "country_code", "release_type", "release_date", "certification", "load_time")
                .dropDuplicates("dt", "movie_id", "country_code", "release_type", "release_date");
    }

    /** 变更事实：来自增量采集的 changes 事件。 */
    public static Dataset<Row> factChangeEvent(Dataset<Row> raw, String dt) {
        return raw.select(
                        col("entity_id"),
                        col("entity_type"),
                        col("ingest_time"))
                .withColumn("event_id", functions.sha2(functions.concat_ws(":", col("entity_type"),
                        col("entity_id"), col("ingest_time")), 256))
                .withColumn("dt", lit(dt).cast("date"))
                .withColumn("event_time", col("ingest_time").divide(lit(1000)).cast("timestamp"))
                .withColumn("window_start", lit(dt).cast("date"))
                .withColumn("window_end", lit(dt).cast("date"))
                .withColumn("load_time", lit(loadTimeOf(dt)).cast("timestamp"))
                .select("dt", "event_id", "entity_type", "entity_id", "window_start", "window_end",
                        "event_time", "load_time")
                .dropDuplicates("dt", "event_id");
    }
}
