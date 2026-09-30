package com.tmdbwh.governance.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.tmdbwh.common.clickhouse.ClickHouseClient;
import com.tmdbwh.common.clickhouse.RowMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** 迁移器逻辑测试：用可控的客户端替身，不依赖真实数据库。 */
class SchemaMigratorTest {

    private static final String TEST_DIR = "clickhouse/test-migrations";

    /** 记录执行的 SQL，并让"已执行校验和"查询返回预设结果。 */
    private static final class FakeClient extends ClickHouseClient {

        final List<String> executed = new ArrayList<>();
        final List<int[]> versions = new ArrayList<>();
        final List<String> checksums = new ArrayList<>();

        FakeClient() {
            // DataSource 有多个抽象方法，无法用 lambda 实现，这里用 mock
            super(org.mockito.Mockito.mock(DataSource.class), "", 1);
        }

        void addApplied(int version, String checksum) {
            versions.add(new int[] {version});
            checksums.add(checksum);
        }

        @Override
        public void execute(String sql) {
            executed.add(sql);
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
            List<T> out = new ArrayList<>();
            if (sql.startsWith("SELECT version") && sql.contains("schema_migrations")) {
                for (int i = 0; i < versions.size(); i++) {
                    try {
                        ResultSet rs = mock(ResultSet.class);
                        when(rs.getInt(1)).thenReturn(versions.get(i)[0]);
                        when(rs.getString(2)).thenReturn(checksums.get(i));
                        out.add(mapper.map(rs));
                    } catch (java.sql.SQLException e) {
                        throw new IllegalStateException(e);
                    }
                }
            }
            return out;
        }

        @Override
        public boolean tableExists(String qualifiedTable) {
            return true;
        }

        @Override
        public void close() {
            // 不真正关闭
        }
    }

    @Test
    void appliesAllScriptsInOrderAndRecordsThem() {
        FakeClient client = new FakeClient();

        SchemaMigrator migrator = SchemaMigrator.using(client, "", "test", TEST_DIR);
        MigrationResult result = migrator.migrate(false);

        assertThat(result.getApplied()).extracting(MigrationResult.Applied::getVersion).containsExactly(1, 2);
        assertThat(result.getBeforeVersion()).isZero();
        assertThat(result.isNoop()).isFalse();
        assertThat(result.summary()).contains("V0 -> V2");
        assertThat(client.executed).anyMatch(sql -> sql.contains("schema_migrations"));
        assertThat(client.executed).anyMatch(sql -> sql.contains("tmdbwh_test.t_one"));
        assertThat(client.executed).anyMatch(sql -> sql.contains("tmdbwh_test.t_two"));
    }

    @Test
    void secondRunIsNoop() throws Exception {
        FakeClient client = new FakeClient();
        for (Migration script : MigrationLoader.fromClasspath(TEST_DIR)) {
            client.addApplied(script.getVersion(), script.getChecksum());
        }

        SchemaMigrator migrator = SchemaMigrator.using(client, "", "test", TEST_DIR);
        MigrationResult result = migrator.migrate(false);

        assertThat(result.isNoop()).isTrue();
        assertThat(result.getAfterVersion()).isEqualTo(2);
        assertThat(result.summary()).contains("无需迁移");
    }

    @Test
    void dryRunExecutesNothingButHistoryTableCreation() {
        FakeClient client = new FakeClient();

        SchemaMigrator migrator = SchemaMigrator.using(client, "", "test", TEST_DIR);
        MigrationResult result = migrator.migrate(true);

        assertThat(result.getApplied()).isEmpty();
        assertThat(client.executed).noneMatch(sql -> sql.contains("tmdbwh_test.t_one"));
    }

    @Test
    void tamperedScriptIsRejected() {
        FakeClient client = new FakeClient();
        // 库中记录的校验和 != 磁盘脚本（已执行脚本被偷偷修改）
        client.addApplied(1, "deadbeefdeadbeef");

        SchemaMigrator migrator = SchemaMigrator.using(client, "", "test", TEST_DIR);

        assertThatThrownBy(() -> migrator.migrate(false))
                .isInstanceOf(MigrationException.class)
                .hasMessageContaining("不一致");
    }

    @Test
    void recordedButMissingScriptIsRejected() {
        FakeClient client = new FakeClient();
        client.addApplied(9, "ffffffffffffffff");

        SchemaMigrator migrator = SchemaMigrator.using(client, "", "test", TEST_DIR);

        assertThatThrownBy(() -> migrator.migrate(false))
                .isInstanceOf(MigrationException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void failingStatementIsRecordedAndReported() {
        FakeClient client = spy(new FakeClient());
        doThrow(new RuntimeException("syntax error")).when(client).execute(contains("tmdbwh_test.t_one"));

        SchemaMigrator migrator = SchemaMigrator.using(client, "", "test", TEST_DIR);

        assertThatThrownBy(() -> migrator.migrate(false))
                .isInstanceOf(MigrationException.class)
                .hasMessageContaining("V1");
        // 失败也要留下痕迹（success=0），便于事后排查
        assertThat(client.executed).anyMatch(sql -> sql.contains("schema_migrations") && sql.contains("INSERT"));
    }

    @Test
    void planTreatsMissingHistoryTableAsEmpty() {
        FakeClient client = spy(new FakeClient());
        when(client.tableExists("governance.schema_migrations")).thenReturn(false);

        SchemaMigrator migrator = SchemaMigrator.using(client, "", "test", TEST_DIR);
        MigrationPlan plan = migrator.plan();

        assertThat(plan.getCurrentVersion()).isZero();
        assertThat(plan.getPending()).hasSize(2);
        assertThat(plan.isUpToDate()).isFalse();
        assertThat(plan.toString()).contains("pending=2");
    }

    @Test
    void withClusterIsAddedOnlyForDdl() {
        SchemaMigrator single = new SchemaMigrator(new FakeClient(), "", "t", TEST_DIR);
        SchemaMigrator clustered = new SchemaMigrator(new FakeClient(), "tmdb_cluster", "t", TEST_DIR);

        assertThat(single.withCluster("CREATE TABLE x (a UInt8) ENGINE = Log")).doesNotContain("ON CLUSTER");
        assertThat(clustered.withCluster("CREATE TABLE x (a UInt8) ENGINE = Log"))
                .endsWith("ON CLUSTER tmdb_cluster");
        assertThat(clustered.withCluster("ALTER TABLE x ADD COLUMN b UInt8"))
                .endsWith("ON CLUSTER tmdb_cluster");
        // 已有 ON CLUSTER 的不重复追加
        assertThat(clustered.withCluster("ALTER TABLE x ON CLUSTER other ADD COLUMN b UInt8"))
                .doesNotContain("tmdb_cluster");
        // 非 DDL 不加
        assertThat(clustered.withCluster("SELECT 1")).isEqualTo("SELECT 1");
    }

    @Test
    void historyReadsFromHistoryTable() {
        FakeClient client = new FakeClient();
        client.addApplied(1, "abc");

        SchemaMigrator migrator = SchemaMigrator.using(client, "", "test", TEST_DIR);

        assertThat(migrator.history()).isNotNull();
        assertThat(client.executed).anyMatch(sql -> sql.contains("CREATE TABLE IF NOT EXISTS governance.schema_migrations"));
    }

    @Test
    void extraDirectoryIsMergedAndSorted() throws Exception {
        FakeClient client = new FakeClient();
        Path extra = Files.createTempDirectory("extra-mig");
        Files.write(extra.resolve("V5__custom.sql"), "SELECT 5;".getBytes(StandardCharsets.UTF_8));

        SchemaMigrator migrator = SchemaMigrator.using(client, "", "test", TEST_DIR);
        MigrationResult result = migrator.migrate(false, extra);

        assertThat(result.getApplied()).extracting(MigrationResult.Applied::getVersion).containsExactly(1, 2, 5);
        assertThat(result.getAfterVersion()).isEqualTo(5);
    }

    @Test
    void appliedRenderingAndDefaults() {
        MigrationResult.Applied applied = new MigrationResult.Applied(3, "x", 4, Duration.ofMillis(12));
        assertThat(applied.getVersion()).isEqualTo(3);
        assertThat(applied.getName()).isEqualTo("x");
        assertThat(applied.getStatements()).isEqualTo(4);
        assertThat(applied.getDuration().toMillis()).isEqualTo(12);
        assertThat(applied.toString()).contains("V3").contains("4").contains("12");
        assertThat(Arrays.asList(1, 2)).hasSize(2);
    }
}
