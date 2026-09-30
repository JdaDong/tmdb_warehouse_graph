package com.tmdbwh.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Cypher 生成：幂等性、参数化与属性顺序。 */
class CypherTest {

    @Test
    void constraintsAreIdempotent() {
        String cypher = Cypher.createConstraint(GraphModel.LABEL_MOVIE);

        // 没有 IF NOT EXISTS 的建约束语句第二次执行会报错，初始化脚本就无法重复运行
        assertThat(cypher).contains("IF NOT EXISTS");
        assertThat(cypher).contains("(n:Movie)").contains("n.movie_id").contains("IS UNIQUE");
    }

    @Test
    void constraintNamesAreStable() {
        assertThat(Cypher.createConstraint(GraphModel.LABEL_MOVIE))
                .contains(GraphModel.constraintName(GraphModel.LABEL_MOVIE));
        assertThat(GraphModel.constraintName(GraphModel.LABEL_MOVIE)).isEqualTo("movie_movie_id_unique");
    }

    @Test
    void indexesUseIfNotExists() {
        assertThat(Cypher.createNameIndex(GraphModel.LABEL_PERSON)).contains("IF NOT EXISTS");
        assertThat(Cypher.createKeyIndex(GraphModel.LABEL_PERSON)).contains("IF NOT EXISTS");
    }

    @Test
    void mergeNodesIsParameterizedAndIdempotent() {
        String cypher = Cypher.mergeNodes(GraphModel.LABEL_MOVIE, List.of("movie_id", "title", "popularity"));

        assertThat(cypher).startsWith("UNWIND $rows AS row");
        assertThat(cypher).contains("MERGE (n:Movie {movie_id: row.movie_id})");
        assertThat(cypher).contains("SET n.title = row.title");
        assertThat(cypher).contains("SET n.popularity = row.popularity");
        // 值一律来自 row，不拼字符串：既防注入也避免类型错误
        assertThat(cypher).doesNotContain("'");
    }

    @Test
    void mergeNodesWithKeyOnlyStillWorks() {
        String cypher = Cypher.mergeNodes(GraphModel.LABEL_MOVIE, List.of("movie_id"));
        assertThat(cypher).contains("MERGE (n:Movie {movie_id: row.movie_id})");
    }

    @Test
    void mergeRelationshipsMatchesBothEndpoints() {
        String cypher = Cypher.mergeRelationships(GraphModel.LABEL_PERSON, GraphModel.LABEL_MOVIE,
                GraphModel.REL_ACTED_IN, List.of("character_name"));

        assertThat(cypher).contains("MATCH (a:Person {person_id: row.from_id})");
        assertThat(cypher).contains("MATCH (b:Movie {movie_id: row.to_id})");
        assertThat(cypher).contains("MERGE (a)-[r:ACTED_IN]->(b)");
        assertThat(cypher).contains("SET r.character_name = row.character_name");
    }

    @Test
    void mergeRelationshipsWithoutProperties() {
        String cypher = Cypher.mergeRelationships(GraphModel.LABEL_MOVIE, GraphModel.LABEL_GENRE,
                GraphModel.REL_HAS_GENRE, List.of());
        assertThat(cypher).contains("MERGE (a)-[r:HAS_GENRE]->(b)");
        assertThat(cypher).doesNotContain("SET r.");
    }

    @Test
    void deleteAllDetaches() {
        // DETACH DELETE 才能删除带关系的节点
        assertThat(Cypher.deleteAll()).isEqualTo("MATCH (n) DETACH DELETE n");
    }

    @Test
    void unknownLabelIsRejected() {
        assertThatThrownBy(() -> Cypher.createConstraint("Alien"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void countQueriesReturnAliasCnt() {
        assertThat(Cypher.countNodes(GraphModel.LABEL_MOVIE)).contains("count(n) AS cnt");
        assertThat(Cypher.countRelationships(GraphModel.REL_ACTED_IN)).contains("count(r) AS cnt");
    }
}
