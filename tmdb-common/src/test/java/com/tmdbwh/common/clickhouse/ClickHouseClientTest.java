package com.tmdbwh.common.clickhouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tmdbwh.common.config.ClickHouseConfig;
import com.tmdbwh.common.exception.ClickHouseException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class ClickHouseClientTest {

    private DataSource ds;
    private Connection conn;
    private Statement st;
    private PreparedStatement ps;
    private ResultSet rs;

    @BeforeEach
    void setUp() throws SQLException {
        ds = mock(DataSource.class);
        conn = mock(Connection.class);
        st = mock(Statement.class);
        ps = mock(PreparedStatement.class);
        rs = mock(ResultSet.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenReturn(st);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
    }

    private ClickHouseClient client(String cluster) {
        return new ClickHouseClient(ds, cluster, 3);
    }

    private static List<Object[]> rows(int n) {
        List<Object[]> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(new Object[] {i, "m" + i});
        }
        return list;
    }

    @Test
    void executeClosesResources() throws SQLException {
        client("").execute("SELECT 1");
        verify(st).execute("SELECT 1");
        verify(st).close();
        verify(conn).close();
    }

    @Test
    void executeWrapsSqlException() throws SQLException {
        when(st.execute(anyString())).thenThrow(new SQLException("Code: 60. Table doesn't exist"));

        assertThatThrownBy(() -> client("").execute("SELECT *   FROM\n missing"))
                .isInstanceOf(ClickHouseException.class)
                .hasMessageContaining("sql=SELECT * FROM missing")
                .hasCauseInstanceOf(SQLException.class);
    }

    @Test
    void longSqlIsAbbreviatedInErrors() throws SQLException {
        when(st.execute(anyString())).thenThrow(new SQLException("boom"));
        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < 400; i++) {
            sql.append("col").append(i).append(", ");
        }
        assertThatThrownBy(() -> client("").execute(sql.toString()))
                .isInstanceOf(ClickHouseException.class)
                .satisfies(e -> assertThat(e.getMessage().length()).isLessThan(ClickHouseException.MAX_SQL_LENGTH + 100))
                .hasMessageEndingWith("...");
    }

    @Test
    void executeScriptRunsStatementsInOrderAndStopsOnError() throws SQLException {
        when(st.execute("CREATE TABLE b")).thenThrow(new SQLException("bad"));

        assertThatThrownBy(() -> client("").executeScript("CREATE DATABASE a; CREATE TABLE b; CREATE TABLE c;"))
                .isInstanceOf(ClickHouseException.class)
                .hasMessageContaining("CREATE TABLE b");
        InOrder order = inOrder(st);
        order.verify(st).execute("CREATE DATABASE a");
        order.verify(st).execute("CREATE TABLE b");
        verify(st, never()).execute("CREATE TABLE c");
    }

    @Test
    void executeScriptReturnsCount() {
        assertThat(client("").executeScript("SELECT 1; -- c\nSELECT 2;")).isEqualTo(2);
    }

    @Test
    void batchInsertFlushesEveryBatchSizeRows() throws SQLException {
        long written = client("").batchInsert("rt.t", Arrays.asList("id", "name"), rows(7));

        assertThat(written).isEqualTo(7);
        verify(conn).prepareStatement("INSERT INTO rt.t (id, name) VALUES (?, ?)");
        verify(ps, times(7)).addBatch();
        verify(ps, times(3)).executeBatch();
        verify(ps).setObject(1, 6);
        verify(ps).setObject(2, "m6");
    }

    @Test
    void batchInsertWithEmptyInputDoesNotExecute() throws SQLException {
        assertThat(client("").batchInsert("rt.t", Arrays.asList("id", "name"), Collections.emptyList(), 10)).isZero();
        verify(ps, never()).executeBatch();
    }

    @Test
    void batchInsertRejectsRowWidthMismatch() {
        List<Object[]> bad = Collections.singletonList(new Object[] {1});
        assertThatThrownBy(() -> client("").batchInsert("rt.t", Arrays.asList("id", "name"), bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("第 1 行");
        assertThatThrownBy(() -> client("").batchInsert("rt.t", Arrays.asList("id"), bad, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void batchInsertReportsCommittedRowsOnFailure() throws SQLException {
        when(ps.executeBatch()).thenReturn(new int[3]).thenThrow(new SQLException("too many parts"));

        assertThatThrownBy(() -> client("").batchInsert("rt.t", Arrays.asList("id", "name"), rows(5)))
                .isInstanceOf(ClickHouseException.class)
                .hasMessageContaining("已提交 3 行");
    }

    @Test
    void queryBindsParametersAndMapsRows() throws SQLException {
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getString(1)).thenReturn("a", "b");

        List<String> result = client("").query("SELECT name FROM t WHERE db = ? AND x > ?", r -> r.getString(1),
                "dwd", 10);

        assertThat(result).containsExactly("a", "b");
        verify(ps).setObject(1, "dwd");
        verify(ps).setObject(2, 10);
        verify(rs).close();
    }

    @Test
    void queryForScalars() throws SQLException {
        when(rs.next()).thenReturn(true, false, false);
        when(rs.getLong(1)).thenReturn(42L);
        ClickHouseClient c = client("");

        assertThat(c.queryForLong("SELECT count() FROM t")).isEqualTo(42L);
        assertThat(c.queryForLong("SELECT count() FROM t WHERE 0")).isZero();
        assertThat(c.queryForString("SELECT version()")).isEmpty();
    }

    @Test
    void tableExistsUsesSystemTables() throws SQLException {
        when(rs.next()).thenReturn(true, false, true, false);
        when(rs.getLong(1)).thenReturn(1L, 0L);
        ClickHouseClient c = client("");

        assertThat(c.tableExists("dwd.dim_movie")).isTrue();
        verify(ps).setObject(1, "dwd");
        verify(ps).setObject(2, "dim_movie");
        assertThat(c.tableExists("local_table")).isFalse();
        assertThatThrownBy(() -> c.tableExists("x; drop")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void replacePartitionFromStagingRunsFullProtocol() throws SQLException {
        when(rs.next()).thenReturn(true, false);
        when(rs.getLong(1)).thenReturn(1234L);
        List<String> loadedInto = new ArrayList<>();

        long rows = client("tmdb").replacePartitionFromStaging("dwd.fact_snapshot", "'2026-09-30'",
                loadedInto::add);

        assertThat(rows).isEqualTo(1234L);
        assertThat(loadedInto).containsExactly("dwd.fact_snapshot_stg");
        InOrder order = inOrder(st);
        order.verify(st).execute("DROP TABLE IF EXISTS dwd.fact_snapshot_stg ON CLUSTER tmdb SYNC");
        order.verify(st).execute("CREATE TABLE IF NOT EXISTS dwd.fact_snapshot_stg ON CLUSTER tmdb AS dwd.fact_snapshot");
        order.verify(st).execute(
                "ALTER TABLE dwd.fact_snapshot ON CLUSTER tmdb REPLACE PARTITION '2026-09-30' FROM dwd.fact_snapshot_stg");
        order.verify(st).execute("DROP TABLE IF EXISTS dwd.fact_snapshot_stg ON CLUSTER tmdb SYNC");
    }

    @Test
    void replacePartitionCleansUpStagingWhenLoaderFails() throws SQLException {
        assertThatThrownBy(() -> client("").replacePartitionFromStaging("dwd.f", "202609", s -> {
            throw new IllegalStateException("spark write failed");
        })).isInstanceOf(IllegalStateException.class);

        verify(st, times(2)).execute("DROP TABLE IF EXISTS dwd.f_stg SYNC");
        verify(st, never()).execute("ALTER TABLE dwd.f REPLACE PARTITION 202609 FROM dwd.f_stg");
    }

    @Test
    void replacePartitionValidatesInputsBeforeTouchingDatabase() throws SQLException {
        assertThatThrownBy(() -> client("").replacePartitionFromStaging("dwd.f", "1=1", s -> { }))
                .isInstanceOf(IllegalArgumentException.class);
        verify(ds, never()).getConnection();
    }

    @Test
    void constructorAndHelpers() throws Exception {
        assertThatThrownBy(() -> new ClickHouseClient(ds, "", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(client(null).getCluster()).isEmpty();
        assertThat(ClickHouseClient.columns("a", "b")).containsExactly("a", "b");

        ClickHouseConfig cfg = new ClickHouseConfig("jdbc:clickhouse://localhost:8123/default", "default", "pw-123456789",
                "", 2, 100);
        assertThat(cfg.toString()).doesNotContain("pw-123456789");
        assertThat(cfg.isClustered()).isFalse();
    }

    @Test
    void closeClosesAutoCloseableDataSource() throws Exception {
        DataSource closable = mock(DataSource.class, org.mockito.Mockito.withSettings()
                .extraInterfaces(AutoCloseable.class));
        new ClickHouseClient(closable, "", 1).close();
        verify((AutoCloseable) closable).close();
    }

    @Test
    void queryWithoutRowsReturnsEmptyList() throws SQLException {
        when(rs.next()).thenReturn(false);
        assertThat(client("").query("SELECT 1", r -> r.getLong(1))).isEmpty();
    }
}
