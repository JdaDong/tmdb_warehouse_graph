package com.tmdbwh.governance.quality;

import java.time.Instant;
import java.util.Objects;

/**
 * 单条质量规则的检查结果。
 *
 * <p>字段与 {@code governance.dq_result} 表一一对应，便于直接批量写入。
 */
public final class QualityResult {

    private final String ruleId;
    private final String database;
    private final String table;
    private final String column;
    private final QualityRule.Severity severity;
    private final boolean passed;
    private final Double metricValue;
    private final Double thresholdValue;
    private final String message;
    private final Instant checkedAt;

    @SuppressWarnings("java:S107")
    public QualityResult(String ruleId, String database, String table, String column,
            QualityRule.Severity severity, boolean passed, Double metricValue, Double thresholdValue,
            String message, Instant checkedAt) {
        this.ruleId = Objects.requireNonNull(ruleId, "ruleId");
        this.database = database == null ? "" : database;
        this.table = table == null ? "" : table;
        this.column = column == null ? "" : column;
        this.severity = Objects.requireNonNull(severity, "severity");
        this.passed = passed;
        this.metricValue = metricValue;
        this.thresholdValue = thresholdValue;
        this.message = message == null ? "" : message;
        this.checkedAt = checkedAt == null ? Instant.now() : checkedAt;
    }

    /** 转成写入 {@code governance.dq_result} 的行（列顺序与表结构一致）。 */
    public Object[] toRow() {
        return new Object[] {
                java.sql.Timestamp.from(checkedAt),
                ruleId,
                database,
                table,
                column,
                severity.name(),
                passed ? 1 : 0,
                metricValue,
                thresholdValue,
                "",
                message
        };
    }

    /** {@code governance.dq_result} 的列名（与 {@link #toRow()} 顺序一致）。 */
    public static java.util.List<String> columns() {
        return java.util.List.of("checked_at", "rule_id", "database", "table", "column", "severity", "passed",
                "metric_value", "threshold_value", "sample_data", "message");
    }

    public String getRuleId() {
        return ruleId;
    }

    public String getDatabase() {
        return database;
    }

    public String getTable() {
        return table;
    }

    public String getColumn() {
        return column;
    }

    public QualityRule.Severity getSeverity() {
        return severity;
    }

    public boolean isPassed() {
        return passed;
    }

    public Double getMetricValue() {
        return metricValue;
    }

    public Double getThresholdValue() {
        return thresholdValue;
    }

    public String getMessage() {
        return message;
    }

    public Instant getCheckedAt() {
        return checkedAt;
    }

    @Override
    public String toString() {
        return "QualityResult{" + ruleId + " passed=" + passed + " metric=" + metricValue
                + " threshold=" + thresholdValue + " " + message + "}";
    }
}
