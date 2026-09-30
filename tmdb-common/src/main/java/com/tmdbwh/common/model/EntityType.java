package com.tmdbwh.common.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * TMDB 实体类型。
 *
 * <p>{@link #getApiPath()} 对应 REST 路径段（/movie/{id}、/tv/{id}、/person/{id}）， {@link #getExportName()} 对应每日 ID
 * 导出文件名前缀（movie_ids_MM_dd_yyyy.json.gz）。
 */
public enum EntityType {
    MOVIE("movie", "movie", true),
    TV("tv", "tv_series", true),
    PERSON("person", "person", true),
    COLLECTION("collection", "collection", false),
    COMPANY("company", "production_company", false),
    KEYWORD("keyword", "keyword", false),
    NETWORK("network", "tv_network", false);

    private final String apiPath;
    private final String exportName;
    private final boolean supportsChanges;

    EntityType(String apiPath, String exportName, boolean supportsChanges) {
        this.apiPath = apiPath;
        this.exportName = exportName;
        this.supportsChanges = supportsChanges;
    }

    /** REST 路径段，同时作为序列化值与湖中目录名。 */
    @JsonValue
    public String getApiPath() {
        return apiPath;
    }

    /** 每日 ID 导出文件前缀。 */
    public String getExportName() {
        return exportName;
    }

    /** 是否支持 /{type}/changes 增量接口（仅 movie / tv / person）。 */
    public boolean supportsChanges() {
        return supportsChanges;
    }

    /**
     * 按路径段或枚举名解析（大小写不敏感）。
     *
     * @throws IllegalArgumentException 未知类型
     */
    @JsonCreator
    public static EntityType fromValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("EntityType 不能为空");
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        for (EntityType t : values()) {
            if (t.apiPath.equals(v) || t.name().toLowerCase(Locale.ROOT).equals(v)) {
                return t;
            }
        }
        throw new IllegalArgumentException("未知的 EntityType: " + value);
    }
}
