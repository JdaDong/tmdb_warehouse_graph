package com.tmdbwh.graph;

import java.util.List;

/**
 * 图模型定义：节点标签、关系类型、属性名。
 *
 * <p>集中定义的原因：Cypher 语句分散在各处时，"关系类型拼错"或"标签大小写不一致"
 * 这类错误只会在运行时才暴露（而且表现为"查不到数据"而不是报错），排查成本极高。
 * 所有语句都通过这里的常量拼接。
 */
public final class GraphModel {

    private GraphModel() {}

    // ============================== 节点标签 ==============================

    public static final String LABEL_MOVIE = "Movie";
    public static final String LABEL_TV = "TvShow";
    public static final String LABEL_PERSON = "Person";
    public static final String LABEL_GENRE = "Genre";
    public static final String LABEL_COMPANY = "Company";
    public static final String LABEL_KEYWORD = "Keyword";
    public static final String LABEL_COUNTRY = "Country";

    /** 所有节点标签。 */
    public static final List<String> ALL_LABELS = List.of(LABEL_MOVIE, LABEL_TV, LABEL_PERSON, LABEL_GENRE,
            LABEL_COMPANY, LABEL_KEYWORD, LABEL_COUNTRY);

    // ============================== 关系类型 ==============================

    public static final String REL_ACTED_IN = "ACTED_IN";
    public static final String REL_DIRECTED = "DIRECTED";
    public static final String REL_CREW_OF = "CREW_OF";
    public static final String REL_HAS_GENRE = "HAS_GENRE";
    public static final String REL_PRODUCED_BY = "PRODUCED_BY";
    public static final String REL_HAS_KEYWORD = "HAS_KEYWORD";
    public static final String REL_FROM_COUNTRY = "FROM_COUNTRY";

    /** 所有关系类型。 */
    public static final List<String> ALL_RELATIONSHIPS = List.of(REL_ACTED_IN, REL_DIRECTED, REL_CREW_OF,
            REL_HAS_GENRE, REL_PRODUCED_BY, REL_HAS_KEYWORD, REL_FROM_COUNTRY);

    // ============================== 主键属性 ==============================

    public static final String PROP_MOVIE_ID = "movie_id";
    public static final String PROP_TV_ID = "tv_id";
    public static final String PROP_PERSON_ID = "person_id";
    public static final String PROP_GENRE_ID = "genre_id";
    public static final String PROP_COMPANY_ID = "company_id";
    public static final String PROP_KEYWORD_ID = "keyword_id";
    public static final String PROP_COUNTRY_CODE = "country_code";

    /** 标签 → 主键属性。 */
    public static String keyPropertyOf(String label) {
        switch (label) {
            case LABEL_MOVIE:
                return PROP_MOVIE_ID;
            case LABEL_TV:
                return PROP_TV_ID;
            case LABEL_PERSON:
                return PROP_PERSON_ID;
            case LABEL_GENRE:
                return PROP_GENRE_ID;
            case LABEL_COMPANY:
                return PROP_COMPANY_ID;
            case LABEL_KEYWORD:
                return PROP_KEYWORD_ID;
            case LABEL_COUNTRY:
                return PROP_COUNTRY_CODE;
            default:
                throw new IllegalArgumentException("未知标签: " + label);
        }
    }

    /** 约束名：标签 + 主键，保证多次创建时名字稳定（配合 IF NOT EXISTS 幂等）。 */
    public static String constraintName(String label) {
        return label.toLowerCase(java.util.Locale.ROOT) + "_" + keyPropertyOf(label) + "_unique";
    }
}
