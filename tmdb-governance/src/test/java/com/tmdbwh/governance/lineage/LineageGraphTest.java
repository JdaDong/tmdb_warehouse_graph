package com.tmdbwh.governance.lineage;

import static org.assertj.core.api.Assertions.assertThat;

import com.typesafe.config.ConfigFactory;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 血缘图：影响分析与上游追溯。 */
class LineageGraphTest {

    private static LineageGraph graph() {
        return LineageGraph.load(ConfigFactory.parseString(
                "edges = ["
                        + "{src = \"ods.raw\", dst = \"dwd.dim\", job = \"j1\", columns = []},"
                        + "{src = \"dwd.dim\", dst = \"dws.sum\", job = \"j2\", columns = [\"movie_id\"]},"
                        + "{src = \"dws.sum\", dst = \"ads.report\", job = \"j3\", columns = []},"
                        + "{src = \"dwd.dim\", dst = \"graph:Movie\", job = \"j4\", columns = []}"
                        + "]"));
    }

    @Test
    void directDownstreamIsFound() {
        assertThat(graph().downstreamOf("dwd.dim")).hasSize(2);
    }

    @Test
    void directUpstreamIsFound() {
        assertThat(graph().upstreamOf("ads.report")).hasSize(1);
        assertThat(graph().upstreamOf("ads.report").get(0).getSource()).isEqualTo("dws.sum");
    }

    @Test
    void allDownstreamIncludesIndirect() {
        // 影响分析必须包含间接下游：重跑 dwd.dim 会影响报表
        assertThat(graph().allDownstream("ods.raw"))
                .containsExactlyInAnyOrder("dwd.dim", "dws.sum", "ads.report", "graph:Movie");
    }

    @Test
    void allUpstreamIncludesIndirect() {
        assertThat(graph().allUpstream("ads.report"))
                .containsExactlyInAnyOrder("dws.sum", "dwd.dim", "ods.raw");
    }

    @Test
    void cyclesDoNotCauseInfiniteLoop() {
        LineageGraph cyclic = LineageGraph.load(ConfigFactory.parseString(
                "edges = [{src = \"a\", dst = \"b\", job = \"j\"}, {src = \"b\", dst = \"a\", job = \"j\"}]"));

        assertThat(cyclic.allDownstream("a")).containsExactlyInAnyOrder("b", "a");
        assertThat(cyclic.allUpstream("a")).containsExactlyInAnyOrder("b", "a");
    }

    @Test
    void unknownTableYieldsEmptyResult() {
        assertThat(graph().downstreamOf("nope")).isEmpty();
        assertThat(graph().upstreamOf("nope")).isEmpty();
    }

    @Test
    void allTablesCollectsBothEndpoints() {
        assertThat(graph().allTables()).contains("ods.raw", "ads.report");
    }

    @Test
    void rowsMatchTableColumnCount() {
        List<Object[]> rows = graph().toRows(Instant.now());

        // 字段级血缘一行一列：dwd.dim -> dws.sum 声明了 movie_id
        assertThat(rows).hasSize(5);
        rows.forEach(row -> assertThat(row).hasSize(LineageGraph.columns().size()));
    }

    @Test
    void graphNodesUseGraphDatabase() {
        // 图节点写成 graph:Movie，没有库的概念，统一按 graph 库落库（避免空值）
        assertThat(LineageGraph.split("graph:Movie")).containsEntry("database", "graph");
        assertThat(LineageGraph.split("graph:Movie")).containsEntry("table", "Movie");
    }

    @Test
    void qualifiedNamesAreSplit() {
        assertThat(LineageGraph.split("dwd.dim_movie")).containsEntry("database", "dwd");
        assertThat(LineageGraph.split("dwd.dim_movie")).containsEntry("table", "dim_movie");
        assertThat(LineageGraph.split("single")).containsEntry("database", "default");
    }

    @Test
    void edgesCarryJobName() {
        assertThat(graph().getEdges()).allSatisfy(edge -> assertThat(edge.getJob()).startsWith("j"));
    }
}
