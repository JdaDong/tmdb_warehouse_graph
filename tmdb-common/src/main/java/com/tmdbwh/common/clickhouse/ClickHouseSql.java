package com.tmdbwh.common.clickhouse;

import com.tmdbwh.common.util.TimeUtils;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * ClickHouse SQL 片段生成与标识符安全校验（纯函数，无 IO）。
 *
 * <p>凡是需要拼接进 SQL 的表名 / 列名 / 分区表达式，都必须经过本类校验，杜绝 SQL 注入。 业务取值一律使用 PreparedStatement 参数绑定，不做字符串拼接。
 */
public final class ClickHouseSql {

    /** 合法标识符：字母或下划线开头，后接字母数字下划线。 */
    private static final Pattern IDENT = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    /**
     * 允许的分区表达式：
     *
     * <ul>
     *   <li>纯数字：{@code 202609}（toYYYYMM 分区）
     *   <li>单引号日期：{@code '2026-09-30'}（toDate 分区）
     *   <li>元组：{@code tuple()} 或 {@code (202609, 'movie')}
     *   <li>分区 ID：{@code ID '202609'}
     * </ul>
     */
    private static final Pattern PARTITION = Pattern.compile(
            "^(\\d{1,10}|'[0-9A-Za-z_\\-]{1,64}'|tuple\\(\\)|ID '[0-9A-Za-z_\\-]{1,128}'"
                    + "|\\((\\s*(\\d{1,10}|'[0-9A-Za-z_\\-]{1,64}')\\s*,)*\\s*(\\d{1,10}|'[0-9A-Za-z_\\-]{1,64}')\\s*\\))$");

    private ClickHouseSql() {}

    /**
     * 校验并返回（可带库名的）表名，例如 {@code dwd.dim_movie}。
     *
     * @throws IllegalArgumentException 非法标识符
     */
    public static String table(String qualifiedName) {
        Objects.requireNonNull(qualifiedName, "table");
        String[] parts = qualifiedName.split("\\.", -1);
        if (parts.length > 2) {
            throw new IllegalArgumentException("非法表名（最多 db.table 两段）: " + qualifiedName);
        }
        for (String p : parts) {
            identifier(p);
        }
        return qualifiedName;
    }

    /**
     * 校验单个标识符（库名 / 列名 / 集群名）。
     *
     * @throws IllegalArgumentException 非法标识符
     */
    public static String identifier(String name) {
        if (name == null || !IDENT.matcher(name).matches()) {
            throw new IllegalArgumentException("非法的 ClickHouse 标识符: " + name);
        }
        return name;
    }

    /**
     * 校验分区表达式。
     *
     * @throws IllegalArgumentException 不在白名单格式内
     */
    public static String partition(String expr) {
        if (expr == null || !PARTITION.matcher(expr.trim()).matches()) {
            throw new IllegalArgumentException("非法的分区表达式: " + expr);
        }
        return expr.trim();
    }

    /** 日期分区字面量：{@code '2026-09-30'}。 */
    public static String datePartition(LocalDate dt) {
        return "'" + TimeUtils.formatDt(dt) + "'";
    }

    /** 月分区字面量：{@code 202609}。 */
    public static String monthPartition(LocalDate dt) {
        return String.valueOf(TimeUtils.yyyymm(dt));
    }

    /** 字符串字面量转义（仅用于 DDL 中的 COMMENT 等无法参数绑定的场景）。 */
    public static String quoteString(String value) {
        if (value == null) {
            return "NULL";
        }
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    /** ON CLUSTER 子句；cluster 为空时返回空串。 */
    public static String onCluster(String cluster) {
        if (cluster == null || cluster.isEmpty()) {
            return "";
        }
        return " ON CLUSTER " + identifier(cluster);
    }

    /** 参数化 INSERT：{@code INSERT INTO t (a, b) VALUES (?, ?)}。 */
    public static String insert(String table, List<String> columns) {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("INSERT 列不能为空");
        }
        String cols = columns.stream().map(ClickHouseSql::identifier).collect(Collectors.joining(", "));
        String marks = columns.stream().map(c -> "?").collect(Collectors.joining(", "));
        return "INSERT INTO " + table(table) + " (" + cols + ") VALUES (" + marks + ")";
    }

    /**
     * 分区原子替换：把 staging 表的指定分区整体替换到目标表。
     *
     * <p>ClickHouse 要求两表结构与分区键完全一致；替换是元数据级原子操作，读者不会看到"半个分区"。 这是离线同步"重跑结果一致"的关键。
     */
    public static String replacePartition(String target, String staging, String partitionExpr, String cluster) {
        return "ALTER TABLE " + table(target) + onCluster(cluster) + " REPLACE PARTITION " + partition(partitionExpr)
                + " FROM " + table(staging);
    }

    /** 删除分区（数据修复 / 生命周期治理）。 */
    public static String dropPartition(String table, String partitionExpr, String cluster) {
        return "ALTER TABLE " + table(table) + onCluster(cluster) + " DROP PARTITION " + partition(partitionExpr);
    }

    /** 以模板表结构创建新表（用于创建 _stg 临时表）。 */
    public static String createTableAs(String newTable, String templateTable, String cluster) {
        return "CREATE TABLE IF NOT EXISTS " + table(newTable) + onCluster(cluster) + " AS " + table(templateTable);
    }

    /** 删除表（幂等）。 */
    public static String dropTable(String table, String cluster) {
        return "DROP TABLE IF EXISTS " + table(table) + onCluster(cluster) + " SYNC";
    }

    /** 清空表（幂等）。 */
    public static String truncate(String table, String cluster) {
        return "TRUNCATE TABLE IF EXISTS " + table(table) + onCluster(cluster);
    }
}
