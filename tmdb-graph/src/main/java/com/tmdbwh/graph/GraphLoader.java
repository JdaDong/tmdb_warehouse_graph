package com.tmdbwh.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 图装载：把数仓的维度与桥接表写入 Neo4j。
 *
 * <p>装载顺序有讲究：<b>先写全部节点，再写关系</b>。
 * 关系语句用 {@code MATCH} 找两端节点，若先写关系会因为节点还不存在而整批落空（而且不报错，静默丢数据）。
 *
 * <p>幂等性：节点与关系都用 {@code MERGE}（见 {@link Cypher}），
 * 因此同一份数据重复装载不会产生重复节点；增量装载直接重跑即可，不需要先删除。
 */
public class GraphLoader {

    private static final Logger LOG = LoggerFactory.getLogger(GraphLoader.class);

    /** 关系类型 → (起始标签, 目标标签)。 */
    public static final Map<String, String[]> RELATIONSHIP_ENDPOINTS = Map.of(
            GraphModel.REL_ACTED_IN, new String[] {GraphModel.LABEL_PERSON, GraphModel.LABEL_MOVIE},
            GraphModel.REL_DIRECTED, new String[] {GraphModel.LABEL_PERSON, GraphModel.LABEL_MOVIE},
            GraphModel.REL_CREW_OF, new String[] {GraphModel.LABEL_PERSON, GraphModel.LABEL_MOVIE},
            GraphModel.REL_HAS_GENRE, new String[] {GraphModel.LABEL_MOVIE, GraphModel.LABEL_GENRE},
            GraphModel.REL_PRODUCED_BY, new String[] {GraphModel.LABEL_MOVIE, GraphModel.LABEL_COMPANY},
            GraphModel.REL_HAS_KEYWORD, new String[] {GraphModel.LABEL_MOVIE, GraphModel.LABEL_KEYWORD},
            GraphModel.REL_FROM_COUNTRY, new String[] {GraphModel.LABEL_MOVIE, GraphModel.LABEL_COUNTRY});

    private final int batchSize;

    public GraphLoader(int batchSize) {
        this.batchSize = batchSize > 0 ? batchSize : 1000;
    }

    /** 装载统计。 */
    public static final class LoadStats {

        private final Map<String, Long> counts = new LinkedHashMap<>();
        private final List<String> skipped = new ArrayList<>();

        void add(String key, long count) {
            counts.merge(key, count, Long::sum);
        }

        void skip(String key) {
            skipped.add(key);
        }

        public Map<String, Long> getCounts() {
            return Map.copyOf(counts);
        }

        public List<String> getSkipped() {
            return List.copyOf(skipped);
        }

        public long total() {
            return counts.values().stream().mapToLong(Long::longValue).sum();
        }

        @Override
        public String toString() {
            return "LoadStats{total=" + total() + ", counts=" + counts + ", skipped=" + skipped + "}";
        }
    }

    /**
     * 全量装载。
     *
     * @param client Neo4j 客户端
     * @param source 数据来源
     * @param labels 要装载的标签（为空时装载全部）
     * @return 装载统计
     */
    public LoadStats load(Neo4jClient client, GraphSource source, Set<String> labels) {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(source, "source");
        LoadStats stats = new LoadStats();

        List<String> targetLabels = labels == null || labels.isEmpty()
                ? GraphModel.ALL_LABELS : new ArrayList<>(labels);

        for (String label : targetLabels) {
            loadNodes(client, source, label, stats);
        }
        for (Map.Entry<String, String[]> entry : RELATIONSHIP_ENDPOINTS.entrySet()) {
            loadEdges(client, source, entry.getKey(), entry.getValue()[0], entry.getValue()[1], stats);
        }
        LOG.info("图装载完成: {}", stats);
        return stats;
    }

    private void loadNodes(Neo4jClient client, GraphSource source, String label, LoadStats stats) {
        List<Map<String, Object>> rows = source.nodes(label);
        if (rows.isEmpty()) {
            stats.skip(label);
            LOG.info("标签 {} 无数据，跳过", label);
            return;
        }
        List<String> properties = propertyNames(rows.get(0), GraphModel.keyPropertyOf(label));
        String cypher = Cypher.mergeNodes(label, properties);
        long written = writeInBatches(client, cypher, rows);
        stats.add(label, written);
        LOG.info("标签 {}: {} 行（属性 {}）", label, rows.size(), properties);
    }

    private void loadEdges(Neo4jClient client, GraphSource source, String relationship, String fromLabel,
            String toLabel, LoadStats stats) {
        List<Map<String, Object>> rows = source.edges(relationship);
        if (rows.isEmpty()) {
            stats.skip(relationship);
            return;
        }
        // 关系属性 = 除 from_id / to_id 之外的列
        List<String> properties = new ArrayList<>();
        for (String key : rows.get(0).keySet()) {
            if (!"from_id".equals(key) && !"to_id".equals(key)) {
                properties.add(key);
            }
        }
        String cypher = Cypher.mergeRelationships(fromLabel, toLabel, relationship, properties);
        long written = writeInBatches(client, cypher, rows);
        stats.add(relationship, written);
        LOG.info("关系 {}: {} 行", relationship, rows.size());
    }

    /** 分批写入：单批过大会让事务超时或内存暴涨。 */
    private long writeInBatches(Neo4jClient client, String cypher, List<Map<String, Object>> rows) {
        long total = 0;
        for (int offset = 0; offset < rows.size(); offset += batchSize) {
            List<Map<String, Object>> batch = rows.subList(offset, Math.min(offset + batchSize, rows.size()));
            total += client.writeBatch(cypher, batch);
            LOG.debug("写入批次 {}/{}", Math.min(offset + batchSize, rows.size()), rows.size());
        }
        return total;
    }

    /**
     * 属性名列表，主键排在最前（Cypher 生成依赖这个顺序）。
     *
     * @throws IllegalArgumentException 缺少主键列——这是配置错误，必须立刻暴露
     */
    static List<String> propertyNames(Map<String, Object> sample, String keyProperty) {
        if (!sample.containsKey(keyProperty)) {
            throw new IllegalArgumentException("数据缺少主键列 " + keyProperty + "，实际列: " + sample.keySet());
        }
        List<String> names = new ArrayList<>();
        names.add(keyProperty);
        for (String key : sample.keySet()) {
            if (!keyProperty.equals(key)) {
                names.add(key);
            }
        }
        return names;
    }
}
