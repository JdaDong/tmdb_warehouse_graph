package com.tmdbwh.governance.lifecycle;

import com.typesafe.config.Config;
import java.util.Objects;

/**
 * 生命周期策略：热数据保留、冷数据下沉、数据过期。
 *
 * <p>三段式设计的业务含义：
 *
 * <ul>
 *   <li>{@code coldAfterDays}：多久后迁到冷存储（对象存储），成本约为本地 SSD 的 1/5，查询仍可用；
 *   <li>{@code ttlDays}：多久后彻底删除（合规要求 + 成本控制）；
 *   <li>{@code partitionDays}：按分区清理的兜底（TTL 是异步的，长期不触发时需要主动 DROP PARTITION）。
 * </ul>
 *
 * <p>{@code ttlDays = 0} 表示"不自动删除"（维度表通常如此）。
 */
public final class LifecyclePolicy {

    private final String table;
    private final int ttlDays;
    private final int coldAfterDays;
    private final int partitionDays;

    public LifecyclePolicy(String table, int ttlDays, int coldAfterDays, int partitionDays) {
        this.table = Objects.requireNonNull(table, "table");
        if (ttlDays < 0 || coldAfterDays < 0 || partitionDays < 0) {
            throw new IllegalArgumentException("生命周期天数不能为负: " + table);
        }
        if (ttlDays > 0 && coldAfterDays > ttlDays) {
            throw new IllegalArgumentException("冷存储时间不能晚于过期时间: " + table);
        }
        this.ttlDays = ttlDays;
        this.coldAfterDays = coldAfterDays;
        this.partitionDays = partitionDays;
    }

    /** 从 HOCON 配置解析。 */
    public static LifecyclePolicy from(Config c) {
        return new LifecyclePolicy(
                c.getString("table"),
                c.hasPath("ttl-days") ? c.getInt("ttl-days") : 0,
                c.hasPath("cold-after-days") ? c.getInt("cold-after-days") : 0,
                c.hasPath("partition-days") ? c.getInt("partition-days") : 0);
    }

    public String getTable() {
        return table;
    }

    public String getDatabase() {
        int dot = table.indexOf('.');
        return dot < 0 ? "default" : table.substring(0, dot);
    }

    public String getTableName() {
        int dot = table.indexOf('.');
        return dot < 0 ? table : table.substring(dot + 1);
    }

    public int getTtlDays() {
        return ttlDays;
    }

    public int getColdAfterDays() {
        return coldAfterDays;
    }

    public int getPartitionDays() {
        return partitionDays;
    }

    @Override
    public String toString() {
        return "LifecyclePolicy{" + table + " ttl=" + ttlDays + "d cold=" + coldAfterDays + "d partition="
                + partitionDays + "d}";
    }
}
