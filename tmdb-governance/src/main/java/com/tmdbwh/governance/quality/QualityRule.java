package com.tmdbwh.governance.quality;

import com.typesafe.config.Config;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 数据质量规则。
 *
 * <p>统一成"计算一个指标值，再与阈值比较"的模型：这样新增规则只需新增一个类型分支，
 * 判定逻辑（比较、严重级别、结果记录）完全复用。
 *
 * <p>严重级别：
 *
 * <ul>
 *   <li>{@code BLOCKER}：必须阻断下游（例如维度为空会让所有报表失真）；
 *   <li>{@code WARN}：记录并告警，但不阻断；
 *   <li>{@code INFO}：仅记录，用于观察趋势。
 * </ul>
 */
public final class QualityRule {

    /** 规则类型。 */
    public enum Type {
        /** 行数下限 / 上限。 */
        ROW_COUNT,
        /** 组合键唯一性。 */
        UNIQUE,
        /** 空值比例。 */
        NOT_NULL,
        /** 值域范围。 */
        RANGE,
        /** 数据新鲜度（分区日期与当前日期的差距）。 */
        FRESHNESS,
        /** 参照完整性（外键值必须存在于维表）。 */
        REFERENTIAL
    }

    /** 严重级别。 */
    public enum Severity {
        BLOCKER, WARN, INFO
    }

    private final String id;
    private final Type type;
    private final String table;
    private final String column;
    private final List<String> columns;
    private final String refTable;
    private final String refColumn;
    private final Double min;
    private final Double max;
    private final Severity severity;

    @SuppressWarnings("java:S107")
    private QualityRule(String id, Type type, String table, String column, List<String> columns, String refTable,
            String refColumn, Double min, Double max, Severity severity) {
        this.id = id;
        this.type = type;
        this.table = table;
        this.column = column;
        this.columns = columns;
        this.refTable = refTable;
        this.refColumn = refColumn;
        this.min = min;
        this.max = max;
        this.severity = severity;
    }

    /** 从 HOCON 配置解析。 */
    public static QualityRule from(Config c) {
        String id = c.getString("id");
        Type type = Type.valueOf(c.getString("type").toUpperCase(Locale.ROOT));
        String table = c.getString("table");
        String column = c.hasPath("column") ? c.getString("column") : "";
        List<String> columns = new ArrayList<>();
        if (c.hasPath("columns")) {
            c.getStringList("columns").forEach(columns::add);
        }
        String refTable = c.hasPath("ref-table") ? c.getString("ref-table") : "";
        String refColumn = c.hasPath("ref-column") ? c.getString("ref-column") : "";
        // 不同规则的阈值字段名不同（min/max、max-null-ratio、max-lag-days…），统一收敛到 min / max
        Double min = threshold(c, "min", "min-null-ratio");
        Double max = threshold(c, "max", "max-null-ratio", "max-duplicates", "max-orphans", "max-lag-days");
        Severity severity = c.hasPath("severity")
                ? Severity.valueOf(c.getString("severity").toUpperCase(Locale.ROOT)) : Severity.WARN;
        return new QualityRule(id, type, table, column, columns, refTable, refColumn, min, max, severity);
    }

    private static Double threshold(Config c, String primary, String... aliases) {
        if (c.hasPath(primary)) {
            return c.getDouble(primary);
        }
        for (String alias : aliases) {
            if (c.hasPath(alias)) {
                return c.getDouble(alias);
            }
        }
        return null;
    }

    /**
     * 生成"指标值查询"SQL。
     *
     * <p>约定：SQL 必须只返回一个数值（{@code metric} 列），便于统一执行与比较。
     *
     * @param dt 业务日期（用于限定分区，避免全表扫描）
     */
    public String toSql(String dt) {
        switch (type) {
            case ROW_COUNT:
                return "SELECT count() AS metric FROM " + table + partitionFilter(dt);
            case UNIQUE:
                return "SELECT count() - uniqExact(" + String.join(", ", columns) + ") AS metric FROM "
                        + table + partitionFilter(dt);
            case NOT_NULL:
                return "SELECT countIf(" + column + " IS NULL) / greatest(count(), 1) AS metric FROM "
                        + table + partitionFilter(dt);
            case RANGE:
                StringBuilder range = new StringBuilder("SELECT countIf(");
                range.append(column).append(" IS NOT NULL AND (");
                boolean hasLower = min != null;
                boolean hasUpper = max != null;
                if (hasLower) {
                    range.append(column).append(" < ").append(min);
                }
                if (hasLower && hasUpper) {
                    range.append(" OR ");
                }
                if (hasUpper) {
                    range.append(column).append(" > ").append(max);
                }
                range.append(")) AS metric FROM ").append(table).append(partitionFilter(dt));
                return range.toString();
            case FRESHNESS:
                return "SELECT dateDiff('day', max(" + column + "), today()) AS metric FROM " + table;
            case REFERENTIAL:
                return "SELECT count() AS metric FROM (SELECT " + column + " FROM " + table
                        + partitionFilter(dt) + ") AS child LEFT JOIN (SELECT " + refColumn + " FROM "
                        + refTable + ") AS parent ON child." + column + " = parent." + refColumn
                        + " WHERE parent." + refColumn + " = 0";
            default:
                throw new IllegalStateException("未支持的规则类型: " + type);
        }
    }

    /**
     * 分区过滤：只检查当天的分区。
     *
     * <p>不加分区条件的全表 count 在亿级表上会把整个集群拖慢，
     * 而且"历史某天数据有问题"与"今天任务失败"是两件事，不应混在一起。
     */
    private String partitionFilter(String dt) {
        return dt == null || dt.isEmpty() ? "" : " WHERE dt = toDate('" + dt + "')";
    }

    public String getId() {
        return id;
    }

    public Type getType() {
        return type;
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

    public String getColumn() {
        return column;
    }

    public List<String> getColumns() {
        return List.copyOf(columns);
    }

    public String getRefTable() {
        return refTable;
    }

    public String getRefColumn() {
        return refColumn;
    }

    public Double getMin() {
        return min;
    }

    public Double getMax() {
        return max;
    }

    public Severity getSeverity() {
        return severity;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof QualityRule)) {
            return false;
        }
        return id.equals(((QualityRule) o).id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "QualityRule{" + id + " type=" + type + " severity=" + severity + "}";
    }
}
