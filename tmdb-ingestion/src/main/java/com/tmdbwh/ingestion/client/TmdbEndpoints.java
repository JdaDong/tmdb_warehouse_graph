package com.tmdbwh.ingestion.client;

import com.tmdbwh.common.model.EntityType;
import java.util.Locale;

/** TMDB API 路径常量。 */
public final class TmdbEndpoints {

    /** API 根路径（供 WireMock 等模拟服务复用）。 */
    public static final String API_ROOT = "/3";

    /** 电影详情：一次请求带回演职员、关键词、分国家上映信息，把 3~4 次请求合并为 1 次。 */
    public static final String MOVIE_APPEND = "credits,keywords,release_dates";
    /** 剧集详情：剧集无 release_dates 子资源。 */
    public static final String TV_APPEND = "credits,keywords";
    /** 人物详情：combined_credits 在 append_to_response 中字段较大，按需单独拉取，这里不默认附加。 */
    public static final String PERSON_APPEND = "";

    private TmdbEndpoints() {}

    /** /movie/{id}、/tv/{id}、/person/{id} 等实体详情路径。 */
    public static String detail(EntityType type, long id) {
        return "/" + type.getApiPath() + "/" + id;
    }

    /** /{type}/changes 增量变更 ID 列表。 */
    public static String changes(EntityType type) {
        return "/" + type.getApiPath() + "/changes";
    }

    /** /trending/{type}/{window}，window 取 day / week。 */
    public static String trending(EntityType type, String window) {
        return "/trending/" + normalizeMediaType(type) + "/" + window;
    }

    /** /{type}/popular 榜单。 */
    public static String popular(EntityType type) {
        return "/" + type.getApiPath() + "/popular";
    }

    /** /genre/{type}/list 类型字典。 */
    public static String genreList(EntityType type) {
        return "/genre/" + (type == EntityType.TV ? "tv" : "movie") + "/list";
    }

    /** /configuration/countries 国家地区字典。 */
    public static String configurationCountries() {
        return "/configuration/countries";
    }

    /** /configuration/languages 语言字典。 */
    public static String configurationLanguages() {
        return "/configuration/languages";
    }

    /** 详情请求默认的 append_to_response（电影 / 剧集不同，人物为空）。 */
    public static String appendToResponse(EntityType type) {
        switch (type) {
            case MOVIE:
                return MOVIE_APPEND;
            case TV:
                return TV_APPEND;
            default:
                return PERSON_APPEND;
        }
    }

    /** 每日 ID 导出文件名（真实 TMDB 为 .json.gz）。 */
    public static String exportFileName(EntityType type, String exportFileDate) {
        return "/" + type.getExportName() + "_ids_" + exportFileDate + ".json.gz";
    }

    /**
     * trending 接口使用的媒体类型名：剧集为 tv（与详情路径一致），人物为 person。
     *
     * @throws IllegalArgumentException 不支持 trending 的实体类型（如公司、系列）
     */
    public static String normalizeMediaType(EntityType type) {
        switch (type) {
            case MOVIE:
            case TV:
            case PERSON:
                return type.getApiPath().toLowerCase(Locale.ROOT);
            default:
                throw new IllegalArgumentException("该实体类型不支持 trending 接口: " + type);
        }
    }
}
