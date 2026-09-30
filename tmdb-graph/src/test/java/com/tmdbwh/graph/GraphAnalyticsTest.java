package com.tmdbwh.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 图分析用例：参数校验与语句约束。 */
class GraphAnalyticsTest {

    @Test
    void everyCaseHasNameAndCypher() {
        List<GraphAnalytics.AnalysisQuery> all = GraphAnalytics.all();
        assertThat(all).isNotEmpty();
        all.forEach(query -> {
            assertThat(query.getName()).isNotBlank();
            assertThat(query.getCypher()).isNotBlank();
            assertThat(query.getDescription()).isNotBlank();
        });
    }

    @Test
    void caseNamesAreUnique() {
        assertThat(GraphAnalytics.names()).doesNotHaveDuplicates();
    }

    @Test
    void allQueriesExceptPathAreLimited() {
        // 图查询不带 LIMIT 会在稠密节点上把实例拖垮；只有 shortestPath 天然返回单行
        GraphAnalytics.all().stream()
                .filter(query -> !"shortest-path".equals(query.getName()))
                .forEach(query -> assertThat(query.getCypher())
                        .as(query.getName() + " 必须带 LIMIT")
                        .contains("LIMIT"));
    }

    @Test
    void shortestPathIsBounded() {
        String cypher = GraphAnalytics.shortestPath(1L, 2L).getCypher();
        assertThat(cypher).contains("*1..6");
        assertThat(GraphAnalytics.shortestPath(1L, 2L).getParameters())
                .containsEntry("from_id", 1L).containsEntry("to_id", 2L);
    }

    @Test
    void casesRequiringEntityIdRejectMissingParameter() {
        assertThatThrownBy(() -> GraphAnalytics.byName("similar-movies", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("movie_id");
        assertThatThrownBy(() -> GraphAnalytics.byName("actor-network", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("person_id");
        assertThatThrownBy(() -> GraphAnalytics.byName("shortest-path", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("from_id");
    }

    @Test
    void unknownCaseIsRejected() {
        assertThatThrownBy(() -> GraphAnalytics.byName("nope", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知分析用例");
    }

    @Test
    void byNameAcceptsUnderscoreAndCaseInsensitive() {
        assertThat(GraphAnalytics.byName("degree_centrality", Map.of()).getName())
                .isEqualTo("degree-centrality");
        assertThat(GraphAnalytics.byName("TOP-COACTORS", Map.of()).getName())
                .isEqualTo("top-coactors");
    }

    @Test
    void collaborationDepthIsClamped() {
        // 深度过大会等价于全图遍历，必须限制
        assertThat(GraphAnalytics.collaborationCircle(1L, 99).getParameters())
                .containsEntry("depth", 4);
        assertThat(GraphAnalytics.collaborationCircle(1L, 0).getParameters())
                .containsEntry("depth", 1);
    }

    @Test
    void coActorQueryAvoidsDuplicatePairs() {
        // a.person_id < b.person_id 保证 (A,B) 与 (B,A) 只统计一次
        assertThat(GraphAnalytics.topCoActors(10).getCypher()).contains("a.person_id < b.person_id");
    }

    @Test
    void similarMoviesUsesSharedFeatures() {
        String cypher = GraphAnalytics.similarMovies(27205L, 10).getCypher();
        assertThat(cypher).contains("{movie_id: $movie_id}");
        assertThat(cypher).contains("shared_features");
        assertThat(GraphAnalytics.similarMovies(27205L, 10).getParameters())
                .containsEntry("movie_id", 27205L).containsEntry("limit", 10);
    }
}
