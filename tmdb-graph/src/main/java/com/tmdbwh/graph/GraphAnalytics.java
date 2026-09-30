package com.tmdbwh.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 图分析用例库。
 *
 * <p>为什么用"参数化 Cypher"而不是 GDS 图算法库：
 *
 * <ul>
 *   <li>GDS 需要额外安装插件，社区版默认没有，装不上时整个分析功能不可用；
 *   <li>本项目的分析需求（度数、共现、路径、相似度）都能用 Cypher 表达，且结果可解释；
 *   <li>真需要 PageRank / 社区发现时，把对应 GDS 调用加到本类即可，调用方不用改。
 * </ul>
 *
 * <p>每个用例都带 {@code LIMIT}：图查询容易在稠密节点上爆炸（一个演员几万条边），
 * 不带限制的查询会拖垮整个实例。
 */
public final class GraphAnalytics {

    private GraphAnalytics() {}

    /** 一个分析用例。 */
    public static final class AnalysisQuery {

        private final String name;
        private final String description;
        private final String cypher;
        private final Map<String, Object> parameters;

        public AnalysisQuery(String name, String description, String cypher, Map<String, Object> parameters) {
            this.name = Objects.requireNonNull(name, "name");
            this.description = Objects.requireNonNull(description, "description");
            this.cypher = Objects.requireNonNull(cypher, "cypher");
            this.parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        }

        public String getName() {
            return name;
        }

        public String getDescription() {
            return description;
        }

        public String getCypher() {
            return cypher;
        }

        public Map<String, Object> getParameters() {
            return parameters;
        }
    }

    /** 全部用例（顺序即文档展示顺序）。 */
    public static List<AnalysisQuery> all() {
        List<AnalysisQuery> queries = new ArrayList<>();
        queries.add(degreeCentrality(50));
        queries.add(topCoActors(20));
        queries.add(directorActorPairs(20));
        queries.add(actorNetwork(0L, 50));
        queries.add(shortestPath(0L, 0L));
        queries.add(genreCoOccurrence(20));
        queries.add(companyGenrePreference(20));
        queries.add(similarMovies(0L, 10));
        queries.add(collaborationCircle(0L, 2));
        queries.add(keywordCentrality(20));
        return queries;
    }

    /** 按名字取用例（用于 CLI）。 */
    public static AnalysisQuery byName(String name, Map<String, Object> parameters) {
        Objects.requireNonNull(name, "name");
        Map<String, Object> params = parameters == null ? Map.of() : parameters;
        String normalized = name.toLowerCase(Locale.ROOT).replace('_', '-');
        int limit = intParam(params, "limit", 20);
        switch (normalized) {
            case "degree-centrality":
                return degreeCentrality(limit);
            case "top-coactors":
                return topCoActors(limit);
            case "director-actor-pairs":
                return directorActorPairs(limit);
            case "actor-network":
                return actorNetwork(longParam(params, "person_id"), limit);
            case "shortest-path":
                return shortestPath(longParam(params, "from_id"), longParam(params, "to_id"));
            case "genre-cooccurrence":
                return genreCoOccurrence(limit);
            case "company-genre":
                return companyGenrePreference(limit);
            case "similar-movies":
                return similarMovies(longParam(params, "movie_id"), limit);
            case "collaboration-circle":
                return collaborationCircle(longParam(params, "person_id"),
                        (int) longParam(params, "depth", 2));
            case "keyword-centrality":
                return keywordCentrality(limit);
            default:
                throw new IllegalArgumentException("未知分析用例: " + name + "（可选: " + names() + "）");
        }
    }

    /** 用例名列表。 */
    public static List<String> names() {
        List<String> result = new ArrayList<>();
        for (AnalysisQuery query : all()) {
            result.add(query.getName());
        }
        return result;
    }

    /**
     * 度数中心性：人物参与的作品数量（出演 + 执导）。
     *
     * <p>用途：找出"高产"影人；也是最简单的中心性指标，作为图算法结果的基准对照。
     */
    public static AnalysisQuery degreeCentrality(int limit) {
        String cypher = "MATCH (p:" + GraphModel.LABEL_PERSON + ")-[r]->(m) "
                + "WHERE type(r) IN ['" + GraphModel.REL_ACTED_IN + "', '" + GraphModel.REL_DIRECTED + "'] "
                + "RETURN p.person_id AS person_id, p.name AS name, count(DISTINCT m) AS degree "
                + "ORDER BY degree DESC LIMIT $limit";
        return new AnalysisQuery("degree-centrality", "人物度数中心性（参与作品数）", cypher,
                Map.of("limit", limit));
    }

    /** 最常合作的演员对（共同出演次数）。 */
    public static AnalysisQuery topCoActors(int limit) {
        String cypher = "MATCH (a:" + GraphModel.LABEL_PERSON + ")-[:ACTED_IN]->(m:" + GraphModel.LABEL_MOVIE
                + ")<-[:ACTED_IN]-(b:" + GraphModel.LABEL_PERSON + ") "
                + "WHERE a.person_id < b.person_id "
                + "RETURN a.name AS actor_a, b.name AS actor_b, count(DISTINCT m) AS shared_movies "
                + "ORDER BY shared_movies DESC LIMIT $limit";
        return new AnalysisQuery("top-coactors", "合作最多的演员对", cypher, Map.of("limit", limit));
    }

    /** 导演与演员的重复合作（"卡司班底"）。 */
    public static AnalysisQuery directorActorPairs(int limit) {
        String cypher = "MATCH (d:" + GraphModel.LABEL_PERSON + ")-[:DIRECTED]->(m:" + GraphModel.LABEL_MOVIE
                + ")<-[:ACTED_IN]-(a:" + GraphModel.LABEL_PERSON + ") "
                + "RETURN d.name AS director, a.name AS actor, count(DISTINCT m) AS collaborations "
                + "ORDER BY collaborations DESC LIMIT $limit";
        return new AnalysisQuery("director-actor-pairs", "导演与演员的重复合作", cypher, Map.of("limit", limit));
    }

    /** 某演员的合作网络（一度邻居）。 */
    public static AnalysisQuery actorNetwork(long personId, int limit) {
        String cypher = "MATCH (p:" + GraphModel.LABEL_PERSON + " {person_id: $person_id})"
                + "-[:ACTED_IN]->(m:" + GraphModel.LABEL_MOVIE + ")"
                + "<-[:ACTED_IN]-(co:" + GraphModel.LABEL_PERSON + ") "
                + "WHERE co <> p "
                + "RETURN co.person_id AS person_id, co.name AS name, count(DISTINCT m) AS shared_movies "
                + "ORDER BY shared_movies DESC LIMIT $limit";
        return new AnalysisQuery("actor-network", "指定演员的合作网络", cypher,
                Map.of("person_id", personId, "limit", limit));
    }

    /**
     * 两个人物之间的最短合作路径（"六度空间"）。
     *
     * <p>限制跳数（1..6）是必须的：无界最短路径在稠密图上等价于全图遍历。
     */
    public static AnalysisQuery shortestPath(long fromId, long toId) {
        String cypher = "MATCH (a:" + GraphModel.LABEL_PERSON + " {person_id: $from_id}), "
                + "(b:" + GraphModel.LABEL_PERSON + " {person_id: $to_id}), "
                + "p = shortestPath((a)-[:ACTED_IN|DIRECTED*1..6]-(b)) "
                + "RETURN [n IN nodes(p) | coalesce(n.name, n.title)] AS path, length(p) AS hops";
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("from_id", fromId);
        params.put("to_id", toId);
        return new AnalysisQuery("shortest-path", "两人之间的最短合作路径", cypher, params);
    }

    /** 类型共现：同一部电影上同时出现的类型。 */
    public static AnalysisQuery genreCoOccurrence(int limit) {
        String cypher = "MATCH (m:" + GraphModel.LABEL_MOVIE + ")-[:HAS_GENRE]->(g1:" + GraphModel.LABEL_GENRE + "), "
                + "(m)-[:HAS_GENRE]->(g2:" + GraphModel.LABEL_GENRE + ") "
                + "WHERE g1.genre_id < g2.genre_id "
                + "RETURN g1.name AS genre_a, g2.name AS genre_b, count(DISTINCT m) AS movies "
                + "ORDER BY movies DESC LIMIT $limit";
        return new AnalysisQuery("genre-cooccurrence", "类型共现关系", cypher, Map.of("limit", limit));
    }

    /** 公司的类型偏好。 */
    public static AnalysisQuery companyGenrePreference(int limit) {
        String cypher = "MATCH (c:" + GraphModel.LABEL_COMPANY + ")<-[:PRODUCED_BY]-(m:" + GraphModel.LABEL_MOVIE
                + ")-[:HAS_GENRE]->(g:" + GraphModel.LABEL_GENRE + ") "
                + "RETURN c.name AS company, g.name AS genre, count(DISTINCT m) AS movies "
                + "ORDER BY movies DESC LIMIT $limit";
        return new AnalysisQuery("company-genre", "制片公司的类型偏好", cypher, Map.of("limit", limit));
    }

    /**
     * 相似电影：共享演员 / 类型 / 关键词越多越相似。
     *
     * <p>这是最基础的"召回"策略，用于验证图数据是否可用——
     * 若相似结果明显不相关，通常说明关系装载有问题（例如 movie_id 类型不一致）。
     */
    public static AnalysisQuery similarMovies(long movieId, int limit) {
        String cypher = "MATCH (m:" + GraphModel.LABEL_MOVIE + " {movie_id: $movie_id})"
                + "-[:ACTED_IN|HAS_GENRE|HAS_KEYWORD]-(x)"
                + "-[:ACTED_IN|HAS_GENRE|HAS_KEYWORD]-(other:" + GraphModel.LABEL_MOVIE + ") "
                + "WHERE other <> m "
                + "RETURN other.movie_id AS movie_id, other.title AS title, count(DISTINCT x) AS shared_features "
                + "ORDER BY shared_features DESC LIMIT $limit";
        return new AnalysisQuery("similar-movies", "相似电影推荐（共享特征数）", cypher,
                Map.of("movie_id", movieId, "limit", limit));
    }

    /** N 度合作圈（指定演员的多层合作网络规模）。 */
    public static AnalysisQuery collaborationCircle(long personId, int depth) {
        int safeDepth = Math.max(1, Math.min(4, depth));
        String cypher = "MATCH (p:" + GraphModel.LABEL_PERSON + " {person_id: $person_id})"
                + "-[:ACTED_IN*1.." + safeDepth + "]-(m:" + GraphModel.LABEL_MOVIE + ") "
                + "RETURN count(DISTINCT m) AS reachable_movies, $depth AS depth";
        return new AnalysisQuery("collaboration-circle", "N 度合作圈覆盖的作品数", cypher,
                Map.of("person_id", personId, "depth", safeDepth));
    }

    /** 关键词中心度：哪些题材是"枢纽"。 */
    public static AnalysisQuery keywordCentrality(int limit) {
        String cypher = "MATCH (k:" + GraphModel.LABEL_KEYWORD + ")<-[:HAS_KEYWORD]-(m:" + GraphModel.LABEL_MOVIE
                + ") RETURN k.name AS keyword, count(DISTINCT m) AS movies "
                + "ORDER BY movies DESC LIMIT $limit";
        return new AnalysisQuery("keyword-centrality", "关键词中心度", cypher, Map.of("limit", limit));
    }

    private static int intParam(Map<String, Object> params, String key, int fallback) {
        Object value = params.get(key);
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    private static long longParam(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        throw new IllegalArgumentException("分析用例缺少必填参数: " + key);
    }

    private static long longParam(Map<String, Object> params, String key, long fallback) {
        Object value = params.get(key);
        return value instanceof Number ? ((Number) value).longValue() : fallback;
    }
}
