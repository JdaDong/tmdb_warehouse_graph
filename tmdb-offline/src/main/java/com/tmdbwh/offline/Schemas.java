package com.tmdbwh.offline;

import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;

/**
 * TMDB 原始报文（ODS payload）的解析 Schema。
 *
 * <p><b>为什么显式声明而不是让 Spark 推断</b>：
 *
 * <ul>
 *   <li>推断需要额外扫一遍全量数据，对每天几十万条明细得不偿失；
 *   <li>推断结果依赖样本，新增字段会导致表结构漂移，破坏下游写入；
 *   <li>显式 Schema 让"上游新增字段"变成"显式决定要不要接入"，而不是意外变更。
 * </ul>
 *
 * <p>未知字段保持忽略：TMDB 新增字段不影响既有作业。
 */
public final class Schemas {

    private Schemas() {}

    /** 电影详情。 */
    public static StructType movie() {
        return new StructType()
                .add("id", DataTypes.LongType)
                .add("title", DataTypes.StringType)
                .add("original_title", DataTypes.StringType)
                .add("original_language", DataTypes.StringType)
                .add("overview", DataTypes.StringType)
                .add("status", DataTypes.StringType)
                .add("release_date", DataTypes.StringType)
                .add("runtime", DataTypes.IntegerType)
                .add("budget", DataTypes.LongType)
                .add("revenue", DataTypes.LongType)
                .add("popularity", DataTypes.DoubleType)
                .add("vote_average", DataTypes.DoubleType)
                .add("vote_count", DataTypes.IntegerType)
                .add("adult", DataTypes.BooleanType)
                .add("belongs_to_collection", new StructType()
                        .add("id", DataTypes.LongType)
                        .add("name", DataTypes.StringType))
                .add("genres", DataTypes.createArrayType(new StructType()
                        .add("id", DataTypes.LongType)
                        .add("name", DataTypes.StringType)))
                .add("production_companies", DataTypes.createArrayType(new StructType()
                        .add("id", DataTypes.LongType)
                        .add("name", DataTypes.StringType)))
                .add("production_countries", DataTypes.createArrayType(new StructType()
                        .add("iso_3166_1", DataTypes.StringType)
                        .add("name", DataTypes.StringType)))
                .add("spoken_languages", DataTypes.createArrayType(new StructType()
                        .add("iso_639_1", DataTypes.StringType)
                        .add("name", DataTypes.StringType)))
                .add("keywords", new StructType()
                        .add("keywords", DataTypes.createArrayType(new StructType()
                                .add("id", DataTypes.LongType)
                                .add("name", DataTypes.StringType))))
                .add("credits", new StructType()
                        .add("cast", DataTypes.createArrayType(new StructType()
                                .add("id", DataTypes.LongType)
                                .add("credit_id", DataTypes.StringType)
                                .add("name", DataTypes.StringType)
                                .add("character", DataTypes.StringType)
                                .add("order", DataTypes.IntegerType)))
                        .add("crew", DataTypes.createArrayType(new StructType()
                                .add("id", DataTypes.LongType)
                                .add("credit_id", DataTypes.StringType)
                                .add("name", DataTypes.StringType)
                                .add("department", DataTypes.StringType)
                                .add("job", DataTypes.StringType))))
                .add("release_dates", new StructType()
                        .add("results", DataTypes.createArrayType(new StructType()
                                .add("iso_3166_1", DataTypes.StringType)
                                .add("release_dates", DataTypes.createArrayType(new StructType()
                                        .add("certification", DataTypes.StringType)
                                        .add("release_date", DataTypes.StringType)
                                        .add("type", DataTypes.IntegerType))))));
    }

    /** 剧集详情（字段与电影不同：name / first_air_date，无 release_dates）。 */
    public static StructType tv() {
        return new StructType()
                .add("id", DataTypes.LongType)
                .add("name", DataTypes.StringType)
                .add("original_name", DataTypes.StringType)
                .add("original_language", DataTypes.StringType)
                .add("overview", DataTypes.StringType)
                .add("status", DataTypes.StringType)
                .add("first_air_date", DataTypes.StringType)
                .add("number_of_seasons", DataTypes.IntegerType)
                .add("number_of_episodes", DataTypes.IntegerType)
                .add("popularity", DataTypes.DoubleType)
                .add("vote_average", DataTypes.DoubleType)
                .add("vote_count", DataTypes.IntegerType)
                .add("adult", DataTypes.BooleanType)
                .add("genres", DataTypes.createArrayType(new StructType()
                        .add("id", DataTypes.LongType)
                        .add("name", DataTypes.StringType)))
                .add("networks", DataTypes.createArrayType(new StructType()
                        .add("id", DataTypes.LongType)
                        .add("name", DataTypes.StringType)))
                .add("production_companies", DataTypes.createArrayType(new StructType()
                        .add("id", DataTypes.LongType)
                        .add("name", DataTypes.StringType)))
                .add("origin_country", DataTypes.createArrayType(DataTypes.StringType))
                .add("keywords", new StructType()
                        .add("results", DataTypes.createArrayType(new StructType()
                                .add("id", DataTypes.LongType)
                                .add("name", DataTypes.StringType))))
                .add("credits", new StructType()
                        .add("cast", DataTypes.createArrayType(new StructType()
                                .add("id", DataTypes.LongType)
                                .add("credit_id", DataTypes.StringType)
                                .add("name", DataTypes.StringType)
                                .add("character", DataTypes.StringType)
                                .add("order", DataTypes.IntegerType)))
                        .add("crew", DataTypes.createArrayType(new StructType()
                                .add("id", DataTypes.LongType)
                                .add("credit_id", DataTypes.StringType)
                                .add("name", DataTypes.StringType)
                                .add("department", DataTypes.StringType)
                                .add("job", DataTypes.StringType))));
    }

    /** 人物详情。 */
    public static StructType person() {
        return new StructType()
                .add("id", DataTypes.LongType)
                .add("name", DataTypes.StringType)
                .add("gender", DataTypes.IntegerType)
                .add("birthday", DataTypes.StringType)
                .add("deathday", DataTypes.StringType)
                .add("place_of_birth", DataTypes.StringType)
                .add("known_for_department", DataTypes.StringType)
                .add("popularity", DataTypes.DoubleType)
                .add("adult", DataTypes.BooleanType)
                .add("biography", DataTypes.StringType);
    }
}
