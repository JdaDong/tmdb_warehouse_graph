package com.tmdbwh.governance.metrics;

import com.typesafe.config.Config;
import java.util.Objects;

/**
 * 指标口径定义。
 *
 * <p>把"指标名 → 出自哪张表、怎么算、谁负责"固化下来，
 * 是为了解决数仓最常见的问题：同一个"热度"在三个报表里三个值，
 * 谁都说不清哪个是对的。定义即契约，报表必须引用这里的定义。
 */
public final class MetricDefinition {

    private final String name;
    private final String table;
    private final String expression;
    private final String owner;
    private final String description;

    public MetricDefinition(String name, String table, String expression, String owner, String description) {
        this.name = Objects.requireNonNull(name, "name");
        this.table = Objects.requireNonNull(table, "table");
        this.expression = Objects.requireNonNull(expression, "expression");
        this.owner = owner == null ? "" : owner;
        this.description = description == null ? "" : description;
        if (name.isBlank() || table.isBlank() || expression.isBlank()) {
            throw new IllegalArgumentException("指标名 / 表 / 计算表达式不能为空: " + name);
        }
    }

    /** 从 HOCON 配置解析。 */
    public static MetricDefinition from(Config c) {
        return new MetricDefinition(
                c.getString("name"),
                c.getString("table"),
                c.getString("expression"),
                c.hasPath("owner") ? c.getString("owner") : "",
                c.hasPath("description") ? c.getString("description") : "");
    }

    public String getName() {
        return name;
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

    public String getExpression() {
        return expression;
    }

    public String getOwner() {
        return owner;
    }

    public String getDescription() {
        return description;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof MetricDefinition)) {
            return false;
        }
        return name.equals(((MetricDefinition) o).name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    @Override
    public String toString() {
        return "MetricDefinition{" + name + " = " + expression + " @" + table + "}";
    }
}
