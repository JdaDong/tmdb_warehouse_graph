package com.tmdbwh.governance.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tmdbwh.common.clickhouse.ClickHouseClient;
import com.tmdbwh.common.clickhouse.RowMapper;
import java.sql.ResultSet;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** 集群配置自检：避免"DDL 只落在一个节点"这类难以发现的事故。 */
class SchemaMigratorClusterTest {

    private static final String TEST_DIR = "clickhouse/test-migrations";

    private static final class Client extends ClickHouseClient {
        private final List<String> clusters;

        Client(List<String> clusters) {
            super(org.mockito.Mockito.mock(DataSource.class), "", 1);
            this.clusters = clusters;
        }

        @Override
        public void execute(String sql) {
            // 不需要
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
            if (sql.contains("system.clusters")) {
                List<T> out = new java.util.ArrayList<>();
                for (String cluster : clusters) {
                    ResultSet rs = mock(ResultSet.class);
                    try {
                        when(rs.getString(1)).thenReturn(cluster);
                        out.add(mapper.map(rs));
                    } catch (java.sql.SQLException e) {
                        throw new IllegalStateException(e);
                    }
                }
                return out;
            }
            return List.of();
        }

        @Override
        public boolean tableExists(String qualifiedTable) {
            return true;
        }

        @Override
        public void close() {
            // 不需要
        }
    }

    @Test
    void unknownClusterNameIsRejected() {
        ClickHouseClient client = new Client(List.of("other_cluster"));

        SchemaMigrator migrator = SchemaMigrator.using(client, "tmdb_cluster", "test", TEST_DIR);

        assertThatThrownBy(() -> migrator.migrate(false))
                .isInstanceOf(MigrationException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void configuredClusterMatchesServerIsAccepted() {
        ClickHouseClient client = new Client(List.of("tmdb_cluster", "default"));

        SchemaMigrator migrator = SchemaMigrator.using(client, "tmdb_cluster", "test", TEST_DIR);
        MigrationResult result = migrator.migrate(false);

        assertThat(result.getApplied()).hasSize(2);
    }

    @Test
    void standaloneServerWithoutClusterIsFine() {
        ClickHouseClient client = new Client(List.of("default"));

        SchemaMigrator migrator = SchemaMigrator.using(client, "", "test", TEST_DIR);

        assertThat(migrator.migrate(false).getApplied()).hasSize(2);
    }

    @Test
    void clusteredServerWithoutClusterNameOnlyWarns() {
        // 服务端有集群但没配集群名：只告警不阻断（也可能是有意为之的单节点操作）
        ClickHouseClient client = new Client(List.of("tmdb_cluster"));

        SchemaMigrator migrator = SchemaMigrator.using(client, "", "test", TEST_DIR);

        assertThat(migrator.migrate(false).getApplied()).hasSize(2);
    }
}
