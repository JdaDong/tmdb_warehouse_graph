package com.tmdbwh.graph.cli;

import com.tmdbwh.common.config.AppConfig;
import com.tmdbwh.common.clickhouse.ClickHouseClient;
import com.tmdbwh.graph.ClickHouseGraphSource;
import com.tmdbwh.graph.Cypher;
import com.tmdbwh.graph.GraphAnalytics;
import com.tmdbwh.graph.GraphLoader;
import com.tmdbwh.graph.GraphModel;
import com.tmdbwh.graph.GraphSource;
import com.tmdbwh.graph.Neo4jClient;
import com.typesafe.config.ConfigFactory;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
 * 图数据库命令行入口。
 *
 * <pre>
 *   graph init                              # 初始化约束与索引
 *   graph load                              # 从数仓装载全图
 *   graph load --label Movie --label Person # 只装载指定标签
 *   graph load --clear --yes                # 清空后重建
 *   graph stats                             # 节点与关系统计
 *   graph analyze --case degree-centrality --param limit=10
 *   graph analyze --case similar-movies --param movie_id=27205
 *   graph cases                             # 列出分析用例
 * </pre>
 */
@Command(name = "graph", mixinStandardHelpOptions = true, version = "graph 1.0",
        description = "Neo4j 影视知识图谱：装载、统计与分析",
        subcommands = {GraphCli.InitCommand.class, GraphCli.LoadCommand.class, GraphCli.StatsCommand.class,
                GraphCli.AnalyzeCommand.class, GraphCli.CasesCommand.class, GraphCli.ClearCommand.class},
        sortOptions = false)
public class GraphCli implements Callable<Integer> {

    private static final Logger LOG = LoggerFactory.getLogger(GraphCli.class);

    @Option(names = {"-c", "--config"}, description = "配置文件路径（HOCON）")
    File configFile;

    AppConfig config() {
        return configFile != null ? AppConfig.load(configFile) : AppConfig.load();
    }

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }

    /** 装载批大小。 */
    static int batchSize() {
        return ConfigFactory.load().getInt("tmdbwh.graph.batch-size");
    }

    /** 是否要求清空前确认。 */
    static boolean requireConfirmForClear() {
        return ConfigFactory.load().getBoolean("tmdbwh.graph.require-confirm-for-clear");
    }

    /** 打开图连接。 */
    static Neo4jClient openGraph(AppConfig config) {
        return Neo4jClient.create(config.getNeo4j());
    }

    /** 打开数仓连接并构造图数据源。 */
    static GraphSource openSource(AppConfig config) {
        return new ClickHouseGraphSource(ClickHouseClient.create(config.getClickhouse()));
    }

    // ============================== init ==============================
    @Command(name = "init", mixinStandardHelpOptions = true, sortOptions = false,
            description = "初始化约束与索引（幂等）")
    static class InitCommand implements Callable<Integer> {

        @ParentCommand
        GraphCli parent;

        @Override
        public Integer call() {
            try (Neo4jClient client = openGraph(parent.config())) {
                if (!client.isReachable()) {
                    LOG.error("Neo4j 不可达，请检查 NEO4J_URI 与网络");
                    return 1;
                }
                client.initSchema();
                LOG.info("图模式初始化完成");
                return 0;
            }
        }
    }

    // ============================== load ==============================
    @Command(name = "load", mixinStandardHelpOptions = true, sortOptions = false,
            description = "从数仓装载图数据（幂等 MERGE）")
    static class LoadCommand implements Callable<Integer> {

        @ParentCommand
        GraphCli parent;

        @Option(names = "--label", description = "只装载指定标签（可重复）")
        List<String> labels;

        @Option(names = "--clear", description = "装载前清空图")
        boolean clear;

        @Option(names = "--yes", description = "确认清空（配合 --clear 使用）")
        boolean confirmed;

        @Override
        public Integer call() {
            if (clear && requireConfirmForClear() && !confirmed) {
                LOG.error("清空图是危险操作，请显式追加 --yes");
                return 2;
            }
            AppConfig config = parent.config();
            try (Neo4jClient client = openGraph(config);
                    ClickHouseClient warehouse = ClickHouseClient.create(config.getClickhouse())) {
                client.initSchema();
                if (clear) {
                    client.run(Cypher.deleteAll());
                    LOG.warn("已清空图数据");
                }
                GraphSource source = new ClickHouseGraphSource(warehouse);
                GraphLoader.LoadStats stats = new GraphLoader(batchSize())
                        .load(client, source, labels == null ? Set.of() : Set.copyOf(labels));
                LOG.info("装载完成: {}", stats);
                return 0;
            }
        }
    }

    // ============================== stats ==============================
    @Command(name = "stats", mixinStandardHelpOptions = true, sortOptions = false,
            description = "统计节点与关系数量")
    static class StatsCommand implements Callable<Integer> {

        @ParentCommand
        GraphCli parent;

        @Override
        public Integer call() {
            try (Neo4jClient client = openGraph(parent.config())) {
                System.out.println("节点:");
                for (String label : GraphModel.ALL_LABELS) {
                    System.out.printf("  %-10s %d%n", label, client.count(Cypher.countNodes(label)));
                }
                System.out.println("关系:");
                for (String relationship : GraphModel.ALL_RELATIONSHIPS) {
                    System.out.printf("  %-14s %d%n", relationship,
                            client.count(Cypher.countRelationships(relationship)));
                }
                return 0;
            }
        }
    }

    // ============================== analyze ==============================
    @Command(name = "analyze", mixinStandardHelpOptions = true, sortOptions = false, description = "执行图分析用例")
    static class AnalyzeCommand implements Callable<Integer> {

        @ParentCommand
        GraphCli parent;

        @Option(names = "--case", required = true, description = "用例名（见 cases 子命令）")
        String useCase;

        @Option(names = "--param", description = "用例参数，形如 k=v（可重复）")
        Map<String, String> params;

        @Override
        public Integer call() {
            Map<String, Object> parameters = parseParams(params);
            GraphAnalytics.AnalysisQuery query;
            try {
                query = GraphAnalytics.byName(useCase, parameters);
            } catch (IllegalArgumentException e) {
                LOG.error("{}", e.getMessage());
                return 2;
            }
            try (Neo4jClient client = openGraph(parent.config())) {
                List<Map<String, Object>> rows = client.query(query.getCypher(), query.getParameters(),
                        record -> {
                            Map<String, Object> row = new LinkedHashMap<>();
                            record.keys().forEach(key -> row.put(key, record.get(key).asObject()));
                            return row;
                        });
                System.out.println("用例: " + query.getName() + " —— " + query.getDescription());
                if (rows.isEmpty()) {
                    System.out.println("  （无结果）");
                }
                rows.forEach(row -> System.out.println("  " + row));
                return 0;
            }
        }

        /** 把 CLI 的 k=v 字符串转成 typed 参数（数字按数字处理，否则按字符串）。 */
        static Map<String, Object> parseParams(Map<String, String> raw) {
            if (raw == null || raw.isEmpty()) {
                return Map.of();
            }
            Map<String, Object> parsed = new LinkedHashMap<>();
            raw.forEach((key, value) -> {
                if (value == null) {
                    return;
                }
                Object typed = value;
                if (value.matches("-?\\d+")) {
                    typed = Long.parseLong(value);
                } else if (value.matches("-?\\d+\\.\\d+")) {
                    typed = Double.parseDouble(value);
                }
                parsed.put(key, typed);
            });
            return parsed;
        }
    }

    // ============================== cases ==============================
    @Command(name = "cases", mixinStandardHelpOptions = true, sortOptions = false, description = "列出分析用例")
    static class CasesCommand implements Callable<Integer> {

        @Override
        public Integer call() {
            for (GraphAnalytics.AnalysisQuery query : GraphAnalytics.all()) {
                System.out.printf("%-22s %s%n", query.getName(), query.getDescription());
            }
            return 0;
        }
    }

    // ============================== clear ==============================
    @Command(name = "clear", mixinStandardHelpOptions = true, sortOptions = false,
            description = "清空图数据（保留约束与索引）")
    static class ClearCommand implements Callable<Integer> {

        @ParentCommand
        GraphCli parent;

        @Option(names = "--yes", required = true, description = "确认清空")
        boolean confirmed;

        @Override
        public Integer call() {
            if (!confirmed) {
                LOG.error("请显式追加 --yes");
                return 2;
            }
            try (Neo4jClient client = openGraph(parent.config())) {
                client.run(Cypher.deleteAll());
                LOG.warn("图数据已清空");
                return 0;
            }
        }
    }

    /** 程序入口。 */
    public static void main(String[] args) {
        System.exit(new CommandLine(new GraphCli()).execute(args));
    }
}
