package com.tmdbwh.graph;

import java.util.List;
import java.util.Map;

/**
 * 图数据源：按标签 / 关系类型提供行数据。
 *
 * <p>抽象出来的原因：装载逻辑（分批、MERGE、统计）与数据来源（ClickHouse、离线文件、测试内存数据）
 * 无关。测试里用内存实现即可验证装载流程，不需要真的起一个 ClickHouse。
 */
public interface GraphSource {

    /**
     * 读取节点行。
     *
     * @param label 标签（{@link GraphModel#ALL_LABELS}）
     * @return 属性 Map 列表，必须包含该标签的主键属性
     */
    List<Map<String, Object>> nodes(String label);

    /**
     * 读取关系行。
     *
     * @param relationship 关系类型
     * @return 行 Map，必须包含 {@code from_id} 与 {@code to_id}
     */
    List<Map<String, Object>> edges(String relationship);
}
