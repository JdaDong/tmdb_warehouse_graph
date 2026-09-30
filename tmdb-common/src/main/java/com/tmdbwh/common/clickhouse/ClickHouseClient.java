package com.tmdbwh.common.clickhouse;

import com.tmdbwh.common.config.ClickHouseConfig;
import com.tmdbwh.common.exception.ClickHouseException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ClickHouse JDBC 客户端封装：连接池 + 常用操作 + 统一异常。
 *
 * <p>主要能力：
 *
 * <ul>
 *   <li>{@link #execute} / {@link #executeScript}：DDL 与多语句脚本（迁移器使用）；
 *   <li>{@link #query} / {@link #queryForLong}：参数化查询；
 *   <li>{@link #batchInsert}：按批大小分批提交，适合百万级以内的同步写入；
 *   <li>{@link #replacePartitionFromStaging}：离线同步"写 _stg → REPLACE PARTITION"的幂等发布流程。
 * </ul>
 *
 * <p>线程安全：内部持有连接池，每次操作借还连接，可在多线程间共享一个实例。
 */
public class ClickHouseClient implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ClickHouseClient.class);

    private static final String DRIVER = "com.clickhouse.jdbc.ClickHouseDriver";

    private final DataSource dataSource;
    private final String cluster;
    private final int defaultBatchSize;

    /**
     * 使用外部数据源构造（测试注入 / 复用已有连接池）。
     *
     * @param dataSource 数据源；若实现了 {@link AutoCloseable}，{@link #close()} 时会一并关闭
     * @param cluster 集群名，空串表示单机
     * @param defaultBatchSize 默认批大小
     */
    public ClickHouseClient(DataSource dataSource, String cluster, int defaultBatchSize) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.cluster = cluster == null ? "" : cluster;
        if (defaultBatchSize <= 0) {
            throw new IllegalArgumentException("defaultBatchSize 必须为正数");
        }
        this.defaultBatchSize = defaultBatchSize;
    }

    /** 按配置创建带 HikariCP 连接池的客户端。 */
    public static ClickHouseClient create(ClickHouseConfig cfg) {
        HikariConfig hc = new HikariConfig();
        hc.setPoolName("clickhouse");
        hc.setDriverClassName(DRIVER);
        hc.setJdbcUrl(cfg.getUrl());
        hc.setUsername(cfg.getUser());
        hc.setPassword(cfg.getPassword());
        hc.setMaximumPoolSize(cfg.getMaxPoolSize());
        hc.setMinimumIdle(1);
        hc.setConnectionTimeout(cfg.getConnectTimeout().toMillis());
        // ClickHouse 为 HTTP 协议，无长连接状态，定期回收避免服务端 keep-alive 超时导致的半开连接
        hc.setMaxLifetime(10 * 60 * 1000L);
        hc.setIdleTimeout(2 * 60 * 1000L);
        hc.addDataSourceProperty("socket_timeout", String.valueOf(cfg.getSocketTimeout().toMillis()));
        hc.addDataSourceProperty("connect_timeout", String.valueOf(cfg.getConnectTimeout().toMillis()));
        hc.addDataSourceProperty("compress", "true");
        return new ClickHouseClient(new HikariDataSource(hc), cfg.getCluster(), cfg.getBatchSize());
    }

    public String getCluster() {
        return cluster;
    }

    /** 执行单条语句（DDL / DML）。 */
    public void execute(String sql) {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new ClickHouseException("执行 SQL 失败", sql, e);
        }
    }

    /**
     * 按顺序执行多语句脚本，遇错即停。
     *
     * @return 执行的语句条数
     */
    public int executeScript(String script) {
        List<String> statements = SqlScriptSplitter.split(script);
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            for (String sql : statements) {
                try {
                    st.execute(sql);
                } catch (SQLException e) {
                    throw new ClickHouseException("执行脚本语句失败", sql, e);
                }
            }
        } catch (SQLException e) {
            throw new ClickHouseException("获取连接失败", script, e);
        }
        return statements.size();
    }

    /**
     * 参数化查询。
     *
     * @param sql 含 ? 占位符的 SQL
     * @param mapper 行映射
     * @param params 按顺序绑定的参数
     */
    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapper.map(rs));
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new ClickHouseException("查询失败", sql, e);
        }
    }

    /** 查询单个 long 值（如 count()）；无结果时返回 0。 */
    public long queryForLong(String sql, Object... params) {
        List<Long> rows = query(sql, rs -> rs.getLong(1), params);
        return rows.isEmpty() ? 0L : rows.get(0);
    }

    /** 查询单个字符串值；无结果或为 NULL 时返回 empty。 */
    public Optional<String> queryForString(String sql, Object... params) {
        List<String> rows = query(sql, rs -> rs.getString(1), params);
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    /** 表是否存在。 */
    public boolean tableExists(String qualifiedTable) {
        ClickHouseSql.table(qualifiedTable);
        String[] parts = qualifiedTable.split("\\.");
        if (parts.length == 2) {
            return queryForLong("SELECT count() FROM system.tables WHERE database = ? AND name = ?",
                    parts[0], parts[1]) > 0;
        }
        return queryForLong("SELECT count() FROM system.tables WHERE database = currentDatabase() AND name = ?",
                parts[0]) > 0;
    }

    /** 使用默认批大小批量写入。 */
    public long batchInsert(String table, List<String> columns, Iterable<Object[]> rows) {
        return batchInsert(table, columns, rows, defaultBatchSize);
    }

    /**
     * 批量写入：每 {@code batchSize} 行提交一次。
     *
     * <p>注意：ClickHouse 每次 INSERT 产生一个 data part，批过小会导致 "Too many parts"，建议 ≥ 1 万行 / 批。
     *
     * @return 写入总行数
     */
    public long batchInsert(String table, List<String> columns, Iterable<Object[]> rows, int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize 必须为正数");
        }
        String sql = ClickHouseSql.insert(table, columns);
        int width = columns.size();
        long total = 0;
        int pending = 0;
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Object[] row : rows) {
                if (row.length != width) {
                    throw new IllegalArgumentException(
                            "行宽 " + row.length + " 与列数 " + width + " 不一致（第 " + (total + pending + 1) + " 行）");
                }
                bind(ps, row);
                ps.addBatch();
                pending++;
                if (pending >= batchSize) {
                    ps.executeBatch();
                    total += pending;
                    pending = 0;
                }
            }
            if (pending > 0) {
                ps.executeBatch();
                total += pending;
            }
        } catch (SQLException e) {
            throw new ClickHouseException("批量写入失败（已提交 " + total + " 行）", sql, e);
        }
        LOG.debug("批量写入 {} 完成，共 {} 行", table, total);
        return total;
    }

    /**
     * 幂等发布一个分区：确保 staging 表存在（结构同目标表）→ 由调用方写入 → 原子替换 → 清理 staging。
     *
     * <p>典型用法见离线同步作业：
     *
     * <pre>{@code
     * client.replacePartitionFromStaging("dwd.fact_movie_daily_snapshot", "'2026-09-30'",
     *         staging -> sparkWriter.write(staging));
     * }</pre>
     *
     * <p>约束：目标表若为 ReplicatedMergeTree，必须使用无参形式 {@code ReplicatedMergeTree()}（默认路径含 {uuid}）， 否则
     * {@code CREATE TABLE ... AS} 复制出的 staging 表会与目标表争用同一 Keeper 路径。迁移脚本已遵循此约定。
     *
     * @param target 目标表
     * @param partitionExpr 分区表达式（经白名单校验）
     * @param loader 向 staging 表写数据的回调，参数为 staging 表名
     * @return staging 表中该分区的行数（可用于与源端做一致性校验）
     */
    public long replacePartitionFromStaging(String target, String partitionExpr, StagingLoader loader) {
        ClickHouseSql.table(target);
        ClickHouseSql.partition(partitionExpr);
        String staging = target + "_stg";
        execute(ClickHouseSql.dropTable(staging, cluster));
        execute(ClickHouseSql.createTableAs(staging, target, cluster));
        try {
            loader.load(staging);
            long rows = queryForLong("SELECT count() FROM " + staging);
            execute(ClickHouseSql.replacePartition(target, staging, partitionExpr, cluster));
            LOG.info("分区发布完成 table={} partition={} rows={}", target, partitionExpr, rows);
            return rows;
        } finally {
            execute(ClickHouseSql.dropTable(staging, cluster));
        }
    }

    /** 向 staging 表写入数据的回调。 */
    @FunctionalInterface
    public interface StagingLoader {
        /** 写入数据到指定 staging 表。 */
        void load(String stagingTable);
    }

    private static void bind(PreparedStatement ps, Object[] params) throws SQLException {
        if (params == null) {
            return;
        }
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }

    /** 便捷方法：单列表。 */
    public static List<String> columns(String... names) {
        List<String> list = new ArrayList<>(names.length);
        Collections.addAll(list, names);
        return list;
    }

    @Override
    public void close() {
        if (dataSource instanceof AutoCloseable) {
            try {
                ((AutoCloseable) dataSource).close();
            } catch (Exception e) {
                LOG.warn("关闭 ClickHouse 连接池失败: {}", e.toString());
            }
        }
    }
}
