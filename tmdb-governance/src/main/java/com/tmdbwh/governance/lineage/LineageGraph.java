package com.tmdbwh.governance.lineage;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 血缘图（表级 + 字段级）。
 *
 * <p>为什么采用"声明式"而不是自动解析 SQL：离线作业大量使用 Spark DataFrame API
 * （不是纯 SQL），SQL 解析器解析不到；实时作业同理。声明式的代价是需要人工维护，
 * 但换来的是"血缘准确且可解释"。生产上常见的做法是两者结合：
 * 核心链路声明式，长尾查询用 system.query_log 补全。
 *
 * <p>两个高频用途：
 *
 * <ul>
 *   <li><b>影响分析</b>：某张表要变更 / 重跑，下游有哪些表会受影响（{@link #downstreamOf}）；
 *   <li><b>上游追溯</b>：报表数字不对，数据是从哪张表来的（{@link #upstreamOf}）。
 * </ul>
 */
public final class LineageGraph {

    /** 一条血缘边。 */
    public static final class Edge {

        private final String source;
        private final String target;
        private final String job;
        private final List<String> columns;

        public Edge(String source, String target, String job, List<String> columns) {
            this.source = Objects.requireNonNull(source, "source");
            this.target = Objects.requireNonNull(target, "target");
            this.job = job == null ? "" : job;
            this.columns = columns == null ? List.of() : List.copyOf(columns);
        }

        public String getSource() {
            return source;
        }

        public String getTarget() {
            return target;
        }

        public String getJob() {
            return job;
        }

        public List<String> getColumns() {
            return columns;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Edge)) {
                return false;
            }
            Edge other = (Edge) o;
            return source.equals(other.source) && target.equals(other.target);
        }

        @Override
        public int hashCode() {
            return Objects.hash(source, target);
        }

        @Override
        public String toString() {
            return source + " -> " + target + " (" + job + ")";
        }
    }

    private final List<Edge> edges;

    public LineageGraph(List<Edge> edges) {
        this.edges = List.copyOf(Objects.requireNonNull(edges, "edges"));
    }

    /** 从默认配置加载。 */
    public static LineageGraph load() {
        return load(ConfigFactory.load().getConfig("tmdbwh.governance.lineage"));
    }

    /** 从指定配置加载。 */
    public static LineageGraph load(Config config) {
        List<Edge> edges = new ArrayList<>();
        for (Config item : config.getConfigList("edges")) {
            List<String> columns = item.hasPath("columns") ? item.getStringList("columns") : List.of();
            edges.add(new Edge(item.getString("src"), item.getString("dst"),
                    item.hasPath("job") ? item.getString("job") : "", columns));
        }
        return new LineageGraph(edges);
    }

    public List<Edge> getEdges() {
        return edges;
    }

    /** 直接下游。 */
    public List<Edge> downstreamOf(String table) {
        List<Edge> result = new ArrayList<>();
        for (Edge edge : edges) {
            if (edge.getSource().equals(table)) {
                result.add(edge);
            }
        }
        return result;
    }

    /** 直接上游。 */
    public List<Edge> upstreamOf(String table) {
        List<Edge> result = new ArrayList<>();
        for (Edge edge : edges) {
            if (edge.getTarget().equals(table)) {
                result.add(edge);
            }
        }
        return result;
    }

    /**
     * 全部下游（递归，含间接）。
     *
     * <p>用 BFS 且带 visited 集合：血缘里出现环（例如回流补数）时不会死循环。
     */
    public Set<String> allDownstream(String table) {
        Set<String> visited = new LinkedHashSet<>();
        java.util.Deque<String> queue = new java.util.ArrayDeque<>();
        queue.add(table);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (Edge edge : downstreamOf(current)) {
                if (visited.add(edge.getTarget())) {
                    queue.add(edge.getTarget());
                }
            }
        }
        return visited;
    }

    /** 全部上游（递归，含间接）。 */
    public Set<String> allUpstream(String table) {
        Set<String> visited = new LinkedHashSet<>();
        java.util.Deque<String> queue = new java.util.ArrayDeque<>();
        queue.add(table);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (Edge edge : upstreamOf(current)) {
                if (visited.add(edge.getSource())) {
                    queue.add(edge.getSource());
                }
            }
        }
        return visited;
    }

    /** 参与血缘的全部表（用于元数据盘点）。 */
    public Set<String> allTables() {
        Set<String> tables = new LinkedHashSet<>();
        for (Edge edge : edges) {
            tables.add(edge.getSource());
            tables.add(edge.getTarget());
        }
        return tables;
    }

    /** 转成写入 {@code governance.lineage_edge} 的行。 */
    public List<Object[]> toRows(Instant capturedAt) {
        List<Object[]> rows = new ArrayList<>();
        for (Edge edge : edges) {
            if (edge.getColumns().isEmpty()) {
                rows.add(row(capturedAt, edge, "", ""));
            } else {
                for (String column : edge.getColumns()) {
                    rows.add(row(capturedAt, edge, column, column));
                }
            }
        }
        return rows;
    }

    /** {@code governance.lineage_edge} 的列名。 */
    public static List<String> columns() {
        return List.of("captured_at", "src_database", "src_table", "src_column", "dst_database", "dst_table",
                "dst_column", "job", "edge_type");
    }

    private static Object[] row(Instant capturedAt, Edge edge, String srcColumn, String dstColumn) {
        Map<String, String> source = split(edge.getSource());
        Map<String, String> target = split(edge.getTarget());
        return new Object[] {
                java.sql.Timestamp.from(capturedAt),
                source.get("database"),
                source.get("table"),
                srcColumn,
                target.get("database"),
                target.get("table"),
                dstColumn,
                edge.getJob(),
                "DECLARED"
        };
    }

    /**
     * 拆分库名与表名。
     *
     * <p>图节点写成 {@code graph:Movie} 形式（没有库的概念），这里统一按 "graph" 库处理，
     * 保证写入时不会有空值（ClickHouse 的 LowCardinality(String) 不接受 null）。
     */
    static Map<String, String> split(String qualified) {
        Map<String, String> result = new LinkedHashMap<>();
        int colon = qualified.indexOf(':');
        if (colon > 0) {
            result.put("database", "graph");
            result.put("table", qualified.substring(colon + 1));
            return result;
        }
        int dot = qualified.indexOf('.');
        if (dot < 0) {
            result.put("database", "default");
            result.put("table", qualified);
        } else {
            result.put("database", qualified.substring(0, dot));
            result.put("table", qualified.substring(dot + 1));
        }
        return result;
    }
}
