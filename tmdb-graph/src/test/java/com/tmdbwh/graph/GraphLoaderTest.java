package com.tmdbwh.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** 图装载：先节点后关系、分批、缺主键时快速失败。 */
class GraphLoaderTest {

    private static Map<String, Object> movie(long id, String title) {
        return Map.of("movie_id", id, "title", title);
    }

    private static final class MapSource implements GraphSource {

        private final Map<String, List<Map<String, Object>>> nodes = new java.util.HashMap<>();
        private final Map<String, List<Map<String, Object>>> edges = new java.util.HashMap<>();

        MapSource withNodes(String label, List<Map<String, Object>> rows) {
            nodes.put(label, rows);
            return this;
        }

        MapSource withEdges(String relationship, List<Map<String, Object>> rows) {
            edges.put(relationship, rows);
            return this;
        }

        @Override
        public List<Map<String, Object>> nodes(String label) {
            return nodes.getOrDefault(label, List.of());
        }

        @Override
        public List<Map<String, Object>> edges(String relationship) {
            return edges.getOrDefault(relationship, List.of());
        }
    }

    @Test
    void loadsNodesBeforeRelationships() {
        Neo4jClient client = mock(Neo4jClient.class);
        when(client.writeBatch(anyString(), anyList())).thenReturn(1L);
        GraphSource source = new MapSource()
                .withNodes(GraphModel.LABEL_MOVIE, List.of(movie(1L, "A")))
                .withEdges(GraphModel.REL_HAS_GENRE, List.of(Map.of("from_id", 1L, "to_id", 28L)));

        GraphLoader.LoadStats stats = new GraphLoader(1000).load(client, source,
                Set.of(GraphModel.LABEL_MOVIE));

        // 关系依赖节点存在：顺序颠倒会静默丢数据
        InOrder order = inOrder(client);
        order.verify(client).writeBatch(anyString(), anyList());
        order.verify(client).writeBatch(anyString(), anyList());
        assertThat(stats.getCounts()).containsKey(GraphModel.LABEL_MOVIE);
        assertThat(stats.getCounts()).containsKey(GraphModel.REL_HAS_GENRE);
        assertThat(stats.getSkipped()).isNotEmpty();
    }

    @Test
    void emptySourceLoadsNothing() {
        Neo4jClient client = mock(Neo4jClient.class);
        GraphLoader.LoadStats stats = new GraphLoader(1000).load(client, new MapSource(), Set.of());

        assertThat(stats.total()).isZero();
        assertThat(stats.getSkipped()).contains(GraphModel.LABEL_MOVIE);
        verify(client, never()).writeBatch(anyString(), anyList());
    }

    @Test
    void largeBatchesAreSplit() {
        Neo4jClient client = mock(Neo4jClient.class);
        when(client.writeBatch(anyString(), anyList())).thenReturn(1L);
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (long i = 0; i < 25; i++) {
            rows.add(movie(i, "M" + i));
        }
        GraphSource source = new MapSource().withNodes(GraphModel.LABEL_MOVIE, rows);

        new GraphLoader(10).load(client, source, Set.of(GraphModel.LABEL_MOVIE));

        // 25 行 / 每批 10 行 = 3 批
        verify(client, org.mockito.Mockito.times(3)).writeBatch(anyString(), anyList());
    }

    @Test
    void missingKeyColumnFailsFast() {
        Neo4jClient client = mock(Neo4jClient.class);
        when(client.writeBatch(anyString(), anyList())).thenReturn(1L);
        GraphSource source = new MapSource()
                .withNodes(GraphModel.LABEL_MOVIE, List.of(Map.of("id", 1L, "title", "A")));

        // 缺少主键列通常是配置的 SQL 忘了 AS 改名，必须立刻报错而不是写进一堆脏节点
        assertThatThrownBy(() -> new GraphLoader(10).load(client, source, Set.of(GraphModel.LABEL_MOVIE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("movie_id");
    }

    @Test
    void propertyNamesPutKeyFirst() {
        List<String> names = GraphLoader.propertyNames(
                Map.of("title", "A", "movie_id", 1L, "popularity", 1.0), "movie_id");
        assertThat(names).startsWith("movie_id");
        assertThat(names).containsExactlyInAnyOrder("movie_id", "title", "popularity");
    }

    @Test
    void relationshipEndpointsCoverAllRelationships() {
        for (String relationship : GraphModel.ALL_RELATIONSHIPS) {
            assertThat(GraphLoader.RELATIONSHIP_ENDPOINTS).containsKey(relationship);
        }
    }

    @Test
    void statsReportTotal() {
        Neo4jClient client = mock(Neo4jClient.class);
        when(client.writeBatch(anyString(), anyList())).thenReturn(5L);
        GraphSource source = new MapSource().withNodes(GraphModel.LABEL_MOVIE, List.of(movie(1L, "A")));

        GraphLoader.LoadStats stats = new GraphLoader(100).load(client, source, Set.of(GraphModel.LABEL_MOVIE));

        assertThat(stats.total()).isPositive();
        assertThat(stats.toString()).contains("LoadStats");
    }
}
