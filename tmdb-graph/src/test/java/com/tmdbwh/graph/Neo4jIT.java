package com.tmdbwh.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 图装载与分析集成测试（需要 Docker）。
 *
 * <p>验证的是单元测试覆盖不到的部分：约束真的能建、MERGE 真的幂等、关系真的能按 ID 关联上。
 * 关系装载是图数据最容易出错的环节（节点 ID 类型不一致会导致"节点都在但关系为 0"）。
 */
@Testcontainers
@Tag("it")
class Neo4jIT {

    @Container
    static final Neo4jContainer<?> NEO4J = new Neo4jContainer<>("neo4j:5.20.0-community")
            .withoutAuthentication();

    private static Neo4jClient client;

    @BeforeAll
    static void setUp() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "未检测到 Docker，跳过集成测试");
        client = new Neo4jClient(org.neo4j.driver.GraphDatabase.driver(NEO4J.getBoltUrl()), "neo4j", true);
        client.initSchema();
    }

    @AfterAll
    static void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void loadingTwiceDoesNotDuplicateNodes() {
        GraphSource source = new GraphSource() {
            @Override
            public List<Map<String, Object>> nodes(String label) {
                return GraphModel.LABEL_MOVIE.equals(label)
                        ? List.of(Map.of("movie_id", 27205L, "title", "Inception")) : List.of();
            }

            @Override
            public List<Map<String, Object>> edges(String relationship) {
                return List.of();
            }
        };

        new GraphLoader(100).load(client, source, java.util.Set.of(GraphModel.LABEL_MOVIE));
        new GraphLoader(100).load(client, source, java.util.Set.of(GraphModel.LABEL_MOVIE));

        assertThat(client.count(Cypher.countNodes(GraphModel.LABEL_MOVIE))).isEqualTo(1L);
    }

    @Test
    void relationshipsAreCreatedWhenBothEndpointsExist() {
        GraphSource source = new GraphSource() {
            @Override
            public List<Map<String, Object>> nodes(String label) {
                if (GraphModel.LABEL_MOVIE.equals(label)) {
                    return List.of(Map.of("movie_id", 27205L, "title", "Inception"));
                }
                if (GraphModel.LABEL_PERSON.equals(label)) {
                    return List.of(Map.of("person_id", 525L, "name", "Nolan"));
                }
                return List.of();
            }

            @Override
            public List<Map<String, Object>> edges(String relationship) {
                return GraphModel.REL_DIRECTED.equals(relationship)
                        ? List.of(Map.of("from_id", 525L, "to_id", 27205L)) : List.of();
            }
        };

        new GraphLoader(100).load(client, source,
                java.util.Set.of(GraphModel.LABEL_MOVIE, GraphModel.LABEL_PERSON));

        assertThat(client.count(Cypher.countRelationships(GraphModel.REL_DIRECTED))).isEqualTo(1L);
    }

    @Test
    void analyticsRunAgainstRealGraph() {
        List<Map<String, Object>> rows = client.query(
                GraphAnalytics.degreeCentrality(10).getCypher(),
                GraphAnalytics.degreeCentrality(10).getParameters(),
                record -> Map.of("name", record.get("name").asString(""),
                        "degree", record.get("degree").asLong(0L)));
        assertThat(rows).isNotNull();
    }
}
