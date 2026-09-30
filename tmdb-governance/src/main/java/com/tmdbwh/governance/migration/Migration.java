package com.tmdbwh.governance.migration;

import java.util.Comparator;
import java.util.Objects;

/**
 * 一条版本化迁移脚本。
 *
 * <p>文件命名：{@code V<version>__<name>.sql}，例如 {@code V3__dwd_fact.sql}。 内容首行必须是形如 {@code -- V3 离线明细层：事实表} 的注释（可选，用于人类阅读），
 * 其余部分为一条或多条以 {@code ;} 分隔的 SQL 语句。
 *
 * <p>校验和用于"防篡改"：脚本内容一旦变更，下次执行会立即报错而不是悄悄跳过。
 */
public final class Migration implements Comparable<Migration> {

    private static final Comparator<Migration> BY_VERSION = Comparator.comparingInt(Migration::getVersion);

    private final int version;
    private final String name;
    private final String fileName;
    private final String sql;
    private final String checksum;

    public Migration(int version, String name, String fileName, String sql, String checksum) {
        if (version <= 0) {
            throw new IllegalArgumentException("迁移版本号必须为正数: " + version);
        }
        this.version = version;
        this.name = Objects.requireNonNull(name, "name");
        this.fileName = Objects.requireNonNull(fileName, "fileName");
        this.sql = Objects.requireNonNull(sql, "sql");
        this.checksum = Objects.requireNonNull(checksum, "checksum");
    }

    public int getVersion() {
        return version;
    }

    public String getName() {
        return name;
    }

    public String getFileName() {
        return fileName;
    }

    public String getSql() {
        return sql;
    }

    /** 内容校验和（SHA-256 前 16 位）。 */
    public String getChecksum() {
        return checksum;
    }

    /** 语句条数（按 {@code ;} 拆分，忽略注释与字符串中的分号）。 */
    public int statementCount() {
        return com.tmdbwh.common.clickhouse.SqlScriptSplitter.split(sql).size();
    }

    @Override
    public int compareTo(Migration other) {
        return BY_VERSION.compare(this, other);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Migration)) {
            return false;
        }
        Migration that = (Migration) o;
        return version == that.version;
    }

    @Override
    public int hashCode() {
        return Objects.hash(version);
    }

    @Override
    public String toString() {
        return "Migration{V" + version + " " + name + ", checksum=" + checksum + "}";
    }
}
