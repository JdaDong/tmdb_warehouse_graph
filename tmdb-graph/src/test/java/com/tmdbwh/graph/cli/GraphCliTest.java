package com.tmdbwh.graph.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.tmdbwh.graph.GraphAnalytics;
import com.tmdbwh.graph.GraphModel;
import java.util.Map;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/** CLI 参数解析。 */
class GraphCliTest {

    @Test
    void helpSucceeds() {
        assertThat(new CommandLine(new GraphCli()).execute("--help")).isZero();
    }

    @Test
    void casesCommandListsAllUseCases() {
        assertThat(new CommandLine(new GraphCli()).execute("cases")).isZero();
        assertThat(GraphAnalytics.names()).isNotEmpty();
    }

    @Test
    void paramsAreParsedWithTypes() {
        Map<String, Object> parsed = GraphCli.AnalyzeCommand.parseParams(
                Map.of("movie_id", "27205", "limit", "10", "name", "Inception", "score", "8.5"));

        assertThat(parsed).containsEntry("movie_id", 27205L);
        assertThat(parsed).containsEntry("limit", 10L);
        assertThat(parsed).containsEntry("name", "Inception");
        assertThat(parsed).containsEntry("score", 8.5);
    }

    @Test
    void emptyParamsYieldEmptyMap() {
        assertThat(GraphCli.AnalyzeCommand.parseParams(null)).isEmpty();
        assertThat(GraphCli.AnalyzeCommand.parseParams(Map.of())).isEmpty();
    }

    @Test
    void analyzeWithValidCaseIsResolvable() {
        GraphAnalytics.AnalysisQuery query = GraphAnalytics.byName("degree-centrality",
                GraphCli.AnalyzeCommand.parseParams(Map.of("limit", "5")));
        assertThat(query.getParameters()).containsEntry("limit", 5L);
    }

    @Test
    void batchSizeDefaultIsPositive() {
        assertThat(GraphCli.batchSize()).isPositive();
    }

    @Test
    void clearRequiresConfirmationByDefault() {
        assertThat(GraphCli.requireConfirmForClear()).isTrue();
    }

    @Test
    void loadWithoutYesRefusesToClear() {
        // 误清空图代价很高（需要全量重装），默认必须二次确认
        assertThat(new CommandLine(new GraphCli()).execute("load", "--clear")).isEqualTo(2);
    }

    @Test
    void graphModelCoversAllLabelsAndRelationships() {
        assertThat(GraphModel.ALL_LABELS).contains(GraphModel.LABEL_MOVIE, GraphModel.LABEL_PERSON);
        assertThat(GraphModel.ALL_RELATIONSHIPS).contains(GraphModel.REL_ACTED_IN, GraphModel.REL_DIRECTED);
        GraphModel.ALL_LABELS.forEach(label -> assertThat(GraphModel.keyPropertyOf(label)).isNotBlank());
    }
}
