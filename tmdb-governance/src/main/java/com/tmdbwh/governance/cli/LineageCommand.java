package com.tmdbwh.governance.cli;

import com.tmdbwh.common.clickhouse.ClickHouseClient;
import com.tmdbwh.governance.ConfigLoader;
import com.tmdbwh.governance.lineage.LineageGraph;
import java.time.Instant;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/** {@code governance lineage}：血缘查询与落库。 */
@Command(name = "lineage", mixinStandardHelpOptions = true, sortOptions = false,
        description = "血缘：影响分析（下游）与来源追溯（上游）")
public class LineageCommand implements Callable<Integer> {

    private static final Logger LOG = LoggerFactory.getLogger(LineageCommand.class);

    @ParentCommand
    com.tmdbwh.governance.cli.GovernanceCli parent;

    @Option(names = "--table", description = "查询指定表的上下游，如 dwd.dim_movie")
    String table;

    @Option(names = "--direction", defaultValue = "downstream",
            description = "方向：downstream | upstream（默认 ${DEFAULT-VALUE}）")
    String direction;

    @Option(names = "--persist", description = "把血缘写入 governance.lineage_edge")
    boolean persist;

    @Override
    public Integer call() {
        LineageGraph graph = LineageGraph.load();
        if (table == null || table.isBlank()) {
            System.out.println("血缘边（共 " + graph.getEdges().size() + " 条）:");
            graph.getEdges().forEach(edge -> System.out.println("  " + edge));
            if (persist) {
                return writeBack(graph);
            }
            return 0;
        }
        if ("upstream".equalsIgnoreCase(direction)) {
            System.out.println("上游（直接）:");
            graph.upstreamOf(table).forEach(edge -> System.out.println("  " + edge));
            System.out.println("上游（全部）: " + graph.allUpstream(table));
        } else {
            System.out.println("下游（直接）:");
            graph.downstreamOf(table).forEach(edge -> System.out.println("  " + edge));
            System.out.println("下游（全部）: " + graph.allDownstream(table));
        }
        return 0;
    }

    private Integer writeBack(LineageGraph graph) {
        var config = ConfigLoader.load(parent.configFile, "governance-lineage");
        try (ClickHouseClient client = ClickHouseClient.create(config.getClickhouse())) {
            long rows = client.batchInsert("governance.lineage_edge", LineageGraph.columns(),
                    graph.toRows(Instant.now()));
            LOG.info("血缘写入完成: {} 行", rows);
            return 0;
        } catch (RuntimeException e) {
            LOG.error("血缘写入失败: {}", e.toString());
            return 1;
        }
    }
}
