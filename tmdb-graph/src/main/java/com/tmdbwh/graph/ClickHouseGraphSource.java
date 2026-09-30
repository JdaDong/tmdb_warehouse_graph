package com.tmdbwh.graph;

import com.tmdbwh.common.clickhouse.ClickHouseClient;
import com.tmdbwh.common.clickhouse.RowMapper;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 从 ClickHouse 数仓读取图数据。
 *
 * <p>查询语句全部来自配置（{@code tmdbwh.graph.queries}）：分层调整时改配置即可，不必改代码。
 *
 * <p>列到属性的映射采用"列名即属性名"，因此 SQL 里要用 {@code AS} 对齐图模型属性名
 * （例如 {@code genre_name AS name}）。
 */
public class ClickHouseGraphSource implements GraphSource {

    private final ClickHouseClient client;
    private final Config nodeQueries;
    private final Config edgeQueries;

    public ClickHouseGraphSource(ClickHouseClient client) {
        this(client, ConfigFactory.load().getConfig("tmdbwh.graph.queries.node"),
                ConfigFactory.load().getConfig("tmdbwh.graph.queries.edge"));
    }

    public ClickHouseGraphSource(ClickHouseClient client, Config nodeQueries, Config edgeQueries) {
        this.client = Objects.requireNonNull(client, "client");
        this.nodeQueries = Objects.requireNonNull(nodeQueries, "nodeQueries");
        this.edgeQueries = Objects.requireNonNull(edgeQueries, "edgeQueries");
    }

    @Override
    public List<Map<String, Object>> nodes(String label) {
        if (!nodeQueries.hasPath(label)) {
            return List.of();
        }
        return select(nodeQueries.getString(label));
    }

    @Override
    public List<Map<String, Object>> edges(String relationship) {
        if (!edgeQueries.hasPath(relationship)) {
            return List.of();
        }
        return select(edgeQueries.getString(relationship));
    }

    /**
     * 执行查询并把每行转成属性 Map。
     *
     * <p>用 {@code SELECT *} 包装原查询，这样即使原 SQL 带 WHERE / ORDER BY 也能安全嵌套。
     */
    private List<Map<String, Object>> select(String sql) {
        String wrapped = "SELECT * FROM (" + sql + ")";
        List<Map<String, Object>> rows = new ArrayList<>();
        client.query(wrapped, (RowMapper<Map<String, Object>>) resultSet -> {
            Map<String, Object> row = new HashMap<>();
            for (int i = 1; i <= resultSet.getMetaData().getColumnCount(); i++) {
                String column = resultSet.getMetaData().getColumnName(i);
                row.put(column, resultSet.getObject(i));
            }
            return row;
        }).forEach(rows::add);
        // NULL 值不写入图：Neo4j 中属性为 null 等价于删除该属性，显式跳过更清晰
        rows.forEach(row -> row.entrySet().removeIf(entry -> entry.getValue() == null));
        return rows;
    }
}
