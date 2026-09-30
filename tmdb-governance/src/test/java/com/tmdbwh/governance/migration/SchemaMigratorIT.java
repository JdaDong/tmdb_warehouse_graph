package com.tmdbwh.governance.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.tmdbwh.common.clickhouse.ClickHouseClient;
import com.tmdbwh.common.config.ClickHouseConfig;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 迁移器集成测试：在真实 ClickHouse 24.3 上执行全部版本化 DDL。
 *
 * <p>覆盖单元测试无法验证的部分：TTL 表达式、storage_policy、物化视图、AggregatingMergeTree
 * 等语法是否真的能被服务端接受，以及"重复执行是否幂等"。
 *
 * <p>需要 Docker；无 Docker 环境自动跳过（mvn verify -Pit）。
 */
@Testcontainers(disabledWithoutDocker = true)
class SchemaMigratorIT {

    @Container
    static final ClickHouseContainer CH = new ClickHouseContainer("clickhouse/clickhouse-server:24.3")
            .withEnv("CLICKHOUSE_DB", "default")
            .withEnv("CLICKHOUSE_USER", "default")
            .withEnv("CLICKHOUSE_PASSWORD", "test")
            .withClasspathResourceMapping("testconf/20-storage-test.xml",
                    "/etc/clickhouse-server/config.d/20-storage-test.xml",
                    org.testcontainers.containers.BindMode.READ_ONLY)
            .withClasspathResourceMapping("testconf/30-cluster-test.xml",
                    "/etc/clickhouse-server/config.d/30-cluster-test.xml",
                    org.testcontainers.containers.BindMode.READ_ONLY);

    @BeforeAll
    static void waitForServer() {
        org.testcontainers.containers.wait.strategy.Wait
                .forHttp("/ping")
                .forPort(8123)
                .withStartupTimeout(java.time.Duration.ofMinutes(3))
                .waitUntilReady(CH);
    }

    @AfterAll
    static void stop() {
        if (CH != null && CH.isRunning()) {
            CH.stop();
        }
    }

    private static ClickHouseClient client() {
        return ClickHouseClient.create(new ClickHouseConfig(CH.getJdbcUrl(), CH.getUsername(), CH.getPassword(),
                "", 2, 1000));
    }

    @Test
    void allMigrationsApplyAndAreIdempotent() {
        try (ClickHouseClient client = client();
                SchemaMigrator migrator = SchemaMigrator.using(client, "", "it")) {

            MigrationResult first = migrator.migrate(false);
            assertThat(first.isNoop()).isFalse();
            assertThat(first.getApplied()).isNotEmpty();

            // 第二次执行必须是空操作（幂等）
            MigrationResult second = migrator.migrate(false);
            assertThat(second.isNoop()).isTrue();
            assertThat(second.getAfterVersion()).isEqualTo(first.getAfterVersion());

            // 历史记录条数 = 执行的脚本数
            assertThat(migrator.history()).hasSize(first.getApplied().size());
        }
    }

    @Test
    void allLayersAndKeyTablesExist() {
        try (ClickHouseClient client = client()) {
            Map<String, List<String>> expected = Map.of(
                    "ods", List.of("ods_movie_raw", "ods_tv_raw", "ods_person_raw", "ods_change_event", "ods_popularity_event"),
                    "dwd", List.of("dim_movie", "dim_person", "fact_movie_credit", "fact_movie_daily_snapshot",
                            "bridge_movie_genre"),
                    "dws", List.of("dws_movie_metric_1d", "dws_genre_year_metric", "dws_person_career"),
                    "ads", List.of("ads_top_movie", "ads_roi_ranking", "ads_person_influence"),
                    "rt", List.of("rt_movie_popularity", "rt_popularity_event", "rt_surge_alert"),
                    "governance", List.of("schema_migrations", "dq_result", "lineage_edge"));
            expected.forEach((database, tables) -> tables.forEach(table ->
                    assertThat(client.tableExists(database + "." + table))
                            .as("%s.%s 应存在", database, table)
                            .isTrue()));

            // 物化视图已建立
            assertThat(client.queryForLong(
                    "SELECT count() FROM system.tables WHERE database = 'rt' AND engine = 'MaterializedView'"))
                    .isGreaterThanOrEqualTo(3);
        }
    }

    @Test
    void coldTierConfigurationIsVisible() {
        try (ClickHouseClient client = client()) {
            long policies = client.queryForLong(
                    "SELECT count() FROM system.storage_policies WHERE policy_name = 'hot_cold'");
            assertThat(policies).as("hot_cold 存储策略应已加载").isGreaterThan(0);

            long tiered = client.queryForLong(
                    "SELECT count() FROM system.tables WHERE database IN ('ods','dwd','dws','ads','rt')"
                            + " AND engine LIKE '%MergeTree%' AND total_bytes >= 0");
            assertThat(tiered).isGreaterThan(10);
        }
    }

    @Test
    void clusteredMigrationUsesOnCluster() {
        // 集群模式：DDL 需追加 ON CLUSTER 并在本机集群上执行成功
        try (ClickHouseClient client = client();
                SchemaMigrator migrator = SchemaMigrator.using(client, "tmdb_cluster", "it",
                        "clickhouse/test-migrations")) {

            MigrationResult result = migrator.migrate(false);

            assertThat(result.getApplied()).extracting(MigrationResult.Applied::getVersion).containsExactly(1, 2);
            assertThat(client.tableExists("tmdbwh_test.t_one")).isTrue();
            assertThat(client.tableExists("tmdbwh_test.t_two")).isTrue();
        }
    }

    @Test
    void materializedViewAggregationWorks() {
        try (ClickHouseClient client = client();
                SchemaMigrator migrator = SchemaMigrator.using(client, "", "it")) {
            migrator.migrate(false);

            // 写入一条实时事件，验证物化视图按小时聚合
            client.execute("INSERT INTO rt.rt_popularity_event (event_id, entity_type, entity_id, event_time,"
                    + " popularity, rank, list_name, ingest_time) VALUES"
                    + " ('e1', 'movie', 27205, '2026-09-30 10:00:00', 100.0, 1, 'trending_movie_day', now())");

            List<Long> counts = client.query(
                    "SELECT countMerge(observations) FROM rt.rt_popularity_hourly"
                            + " WHERE hour_start = '2026-09-30 10:00:00' AND entity_id = 27205",
                    (ResultSet rs) -> rs.getLong(1));
            assertThat(counts).containsExactly(1L);
        }
    }
}
