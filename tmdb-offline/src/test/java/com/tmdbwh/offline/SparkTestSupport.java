package com.tmdbwh.offline;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;

/**
 * 测试辅助：本地 Spark 会话与 ODS 样本数据。
 *
 * <p>Spark 用 local[*] 模式真跑，因此这里构造的数据会真的经过解析、清洗、聚合，
 * 而不是靠 mock 断言"调用过某个方法"。
 */
public final class SparkTestSupport {

    private SparkTestSupport() {}

    /** 本地会话（Iceberg 使用 hadoop 类型 Catalog，仓库指向临时目录）。 */
    public static SparkSession session(Path warehouse) {
        return SparkSession.builder()
                .master("local[2]")
                .appName("tmdbwh-offline-test")
                .config("spark.ui.enabled", "false")
                .config("spark.sql.shuffle.partitions", "2")
                .config("spark.sql.session.timeZone", "UTC")
                .config("spark.sql.extensions", "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions")
                .config("spark.sql.catalog.lake", "org.apache.iceberg.spark.SparkCatalog")
                .config("spark.sql.catalog.lake.type", "hadoop")
                .config("spark.sql.catalog.lake.warehouse", warehouse.toUri().toString())
                .config("spark.sql.defaultCatalog", "lake")
                .config("spark.sql.warehouse.dir", warehouse.resolve("spark-warehouse").toString())
                .getOrCreate();
    }

    /** 构造 ODS 原始记录（entity_type / entity_id / dt / payload / ingest_time）。 */
    public static Dataset<Row> raw(SparkSession spark, String entityType, String dt, String... payloads) {
        StructType schema = new StructType()
                .add("schema_version", DataTypes.IntegerType)
                .add("entity_type", DataTypes.StringType)
                .add("entity_id", DataTypes.LongType)
                .add("dt", DataTypes.StringType)
                .add("ingest_time", DataTypes.LongType)
                .add("source", DataTypes.StringType)
                .add("payload", DataTypes.StringType);
        List<Row> rows = new java.util.ArrayList<>();
        long now = System.currentTimeMillis();
        for (String payload : payloads) {
            long id = com.tmdbwh.common.json.JsonUtils.readTree(payload).get("id").asLong();
            rows.add(RowFactory.create(1, entityType, id, dt, now, "tmdb.details", payload));
        }
        return spark.createDataFrame(rows, schema);
    }

    /** 一条最小可用的电影报文（含演职员、关键词、上映信息、预算与票房）。 */
    public static String moviePayload(long id, String title, double popularity, long budget, long revenue) {
        return "{\"id\":" + id + ",\"title\":\"" + title + "\",\"original_title\":\"" + title + "\","
                + "\"original_language\":\"en\",\"overview\":\"测试简介\",\"status\":\"Released\","
                + "\"release_date\":\"2010-07-16\",\"runtime\":148,"
                + "\"budget\":" + budget + ",\"revenue\":" + revenue + ","
                + "\"popularity\":" + popularity + ",\"vote_average\":8.4,\"vote_count\":35000,"
                + "\"adult\":false,"
                + "\"belongs_to_collection\":{\"id\":263,\"name\":\"测试系列\"},"
                + "\"genres\":[{\"id\":28,\"name\":\"Action\"},{\"id\":878,\"name\":\"Science Fiction\"}],"
                + "\"production_companies\":[{\"id\":923,\"name\":\"Legendary Pictures\"},"
                + "{\"id\":9996,\"name\":\"Syncopy\"}],"
                + "\"production_countries\":[{\"iso_3166_1\":\"US\",\"name\":\"United States\"},"
                + "{\"iso_3166_1\":\"GB\",\"name\":\"United Kingdom\"}],"
                + "\"spoken_languages\":[{\"iso_639_1\":\"en\",\"name\":\"English\"}],"
                + "\"keywords\":{\"keywords\":[{\"id\":825,\"name\":\"dream\"},{\"id\":616,\"name\":\"subconscious\"}]},"
                + "\"credits\":{\"cast\":["
                + "{\"id\":6193,\"credit_id\":\"c1\",\"name\":\"Leonardo DiCaprio\",\"character\":\"Cobb\",\"order\":0},"
                + "{\"id\":24045,\"credit_id\":\"c2\",\"name\":\"Joseph Gordon-Levitt\",\"character\":\"Arthur\",\"order\":1}],"
                + "\"crew\":["
                + "{\"id\":525,\"credit_id\":\"c3\",\"name\":\"Christopher Nolan\",\"department\":\"Directing\",\"job\":\"Director\"},"
                + "{\"id\":525,\"credit_id\":\"c4\",\"name\":\"Christopher Nolan\",\"department\":\"Writing\",\"job\":\"Screenplay\"}]},"
                + "\"release_dates\":{\"results\":["
                + "{\"iso_3166_1\":\"US\",\"release_dates\":["
                + "{\"certification\":\"PG-13\",\"release_date\":\"2010-07-16T00:00:00.000Z\",\"type\":3}]},"
                + "{\"iso_3166_1\":\"GB\",\"release_dates\":["
                + "{\"certification\":\"12A\",\"release_date\":\"2010-07-21T00:00:00.000Z\",\"type\":3}]}]}}";
    }

    /** 一条最小可用的剧集报文。 */
    public static String tvPayload(long id, String name, double popularity) {
        return "{\"id\":" + id + ",\"name\":\"" + name + "\",\"original_name\":\"" + name + "\","
                + "\"original_language\":\"en\",\"overview\":\"剧集简介\",\"status\":\"Ended\","
                + "\"first_air_date\":\"2011-04-17\",\"number_of_seasons\":8,\"number_of_episodes\":73,"
                + "\"popularity\":" + popularity + ",\"vote_average\":9.2,\"vote_count\":21000,\"adult\":false,"
                + "\"genres\":[{\"id\":18,\"name\":\"Drama\"}],"
                + "\"networks\":[{\"id\":49,\"name\":\"HBO\"}],"
                + "\"production_companies\":[{\"id\":76043,\"name\":\"Revolution Sun Studios\"}],"
                + "\"origin_country\":[\"US\"],"
                + "\"keywords\":{\"results\":[{\"id\":6091,\"name\":\"war\"}]},"
                + "\"credits\":{\"cast\":[{\"id\":22970,\"credit_id\":\"t1\",\"name\":\"Peter Dinklage\","
                + "\"character\":\"Tyrion Lannister\",\"order\":0}],"
                + "\"crew\":[{\"id\":9813,\"credit_id\":\"t2\",\"name\":\"David Benioff\","
                + "\"department\":\"Writing\",\"job\":\"Writer\"}]}}";
    }

    /** 一条最小可用的人物报文。 */
    public static String personPayload(long id, String name, double popularity) {
        return "{\"id\":" + id + ",\"name\":\"" + name + "\",\"gender\":2,\"birthday\":\"1970-07-30\","
                + "\"deathday\":null,\"place_of_birth\":\"London, England, UK\","
                + "\"known_for_department\":\"Directing\",\"popularity\":" + popularity + ",\"adult\":false,"
                + "\"biography\":\"英国导演、编剧。\"}";
    }

    /** 取某列的全部值（便于断言）。 */
    public static List<Object> values(Dataset<Row> dataset, String column) {
        return dataset.select(column).collectAsList().stream()
                .map(r -> r.get(0))
                .collect(java.util.stream.Collectors.toList());
    }

    /** 取首行的某个字段。 */
    public static Object first(Dataset<Row> dataset, String column) {
        return dataset.select(column).head().get(0);
    }

    /** 列名列表。 */
    public static List<String> columns(Dataset<Row> dataset) {
        return Arrays.asList(dataset.columns());
    }
}
