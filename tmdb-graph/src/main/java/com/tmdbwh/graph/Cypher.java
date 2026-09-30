package com.tmdbwh.graph;

import java.util.List;
import java.util.Locale;

/**
 * Cypher 语句生成。
 *
 * <p>三条硬性约定（违反任意一条都会在数据量上来后出问题）：
 *
 * <ul>
 *   <li><b>全部使用 MERGE + SET</b>：装载必须幂等，重复执行不产生重复节点 / 关系；
 *   <li><b>全部参数化</b>：不在语句里拼接值（既防注入，也避免类型错误）；
 *   <li><b>批量用 UNWIND</b>：逐条 commit 在百万级节点下会慢到不可用。
 * </ul>
 *
 * <p>约束与索引使用 {@code IF NOT EXISTS}，保证初始化脚本可重复执行。
 */
public final class Cypher {

    private Cypher() {}

    /** 唯一性约束（幂等）。 */
    public static String createConstraint(String label) {
        return "CREATE CONSTRAINT " + GraphModel.constraintName(label) + " IF NOT EXISTS"
                + " FOR (n:" + label + ") REQUIRE n." + GraphModel.keyPropertyOf(label) + " IS UNIQUE";
    }

    /** 查询用索引（幂等）：标题 / 名称的模糊检索。 */
    public static String createNameIndex(String label) {
        return "CREATE INDEX " + label.toLowerCase(Locale.ROOT) + "_name_index IF NOT EXISTS"
                + " FOR (n:" + label + ") ON (n.name)";
    }

    /** 关系两端节点的索引（幂等）：加速按 ID 关联。 */
    public static String createKeyIndex(String label) {
        return "CREATE INDEX " + label.toLowerCase(Locale.ROOT) + "_key_index IF NOT EXISTS"
                + " FOR (n:" + label + ") ON (n." + GraphModel.keyPropertyOf(label) + ")";
    }

    /**
     * 批量 MERGE 节点。
     *
     * @param label 标签
     * @param properties 需要写入的属性名列表（第一个必须是主键）
     */
    public static String mergeNodes(String label, List<String> properties) {
        String key = GraphModel.keyPropertyOf(label);
        StringBuilder setClause = new StringBuilder();
        for (String property : properties) {
            if (key.equals(property)) {
                continue;
            }
            setClause.append(", n.").append(property).append(" = row.").append(property);
        }
        return "UNWIND $rows AS row "
                + "MERGE (n:" + label + " {" + key + ": row." + key + "}) "
                + (setClause.length() == 0 ? "" : "SET n." + key + " = row." + key + setClause + " ")
                // 记录更新时间：便于增量装载时判断哪些节点是本次真正写入的
                + "SET n.updated_at = timestamp()";
    }

    /**
     * 批量 MERGE 关系。
     *
     * @param fromLabel 起始节点标签
     * @param toLabel 目标节点标签
     * @param relationship 关系类型
     * @param properties 关系属性（可空；为空时只做 MERGE）
     */
    public static String mergeRelationships(String fromLabel, String toLabel, String relationship,
            List<String> properties) {
        String fromKey = GraphModel.keyPropertyOf(fromLabel);
        String toKey = GraphModel.keyPropertyOf(toLabel);
        StringBuilder setClause = new StringBuilder();
        if (properties != null) {
            for (String property : properties) {
                setClause.append(", r.").append(property).append(" = row.").append(property);
            }
        }
        return "UNWIND $rows AS row "
                + "MATCH (a:" + fromLabel + " {" + fromKey + ": row.from_id}) "
                + "MATCH (b:" + toLabel + " {" + toKey + ": row.to_id}) "
                + "MERGE (a)-[r:" + relationship + "]->(b)"
                + (setClause.length() == 0 ? " " : setClause + " ")
                + "SET r.updated_at = timestamp()";
    }

    /** 清空图（保留约束）：用于全量重建，生产环境需二次确认。 */
    public static String deleteAll() {
        return "MATCH (n) DETACH DELETE n";
    }

    /** 统计节点数（按标签）。 */
    public static String countNodes(String label) {
        return "MATCH (n:" + label + ") RETURN count(n) AS cnt";
    }

    /** 统计关系数（按类型）。 */
    public static String countRelationships(String relationship) {
        return "MATCH ()-[r:" + relationship + "]->() RETURN count(r) AS cnt";
    }
}
