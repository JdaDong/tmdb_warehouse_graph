package com.tmdbwh.offline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** ClickHouse 同步 SQL 的生成。 */
class OfflineSqlRunnerTest {

    private static final String CREATE_LIKE =
            "CREATE TABLE IF NOT EXISTS dws.dws_movie_metric_1d_stg AS dws.dws_movie_metric_1d";

    @Test
    void partitionIdMatchesMonthlyPartitioning() {
        // 迁移脚本中 PARTITION BY toYYYYMM(dt)，因此分区 ID 为 yyyyMM
        assertThat(OfflineSqlRunner.partitionId("2026-09-30")).isEqualTo("202609");
        assertThat(OfflineSqlRunner.partitionId("2026-01-01")).isEqualTo("202601");
    }

    @Test
    void replacePartitionDropsAndReattachesInOrder() {
        List<String> sql = new OfflineSqlRunner("").replacePartition(
                "dws", "dws_movie_metric_1d", "2026-09-30", "_stg_20260930", CREATE_LIKE);

        assertThat(sql).hasSize(7);
        assertThat(sql.get(0)).contains("DROP TABLE IF EXISTS dws.dws_movie_metric_1d_stg_20260930");
        assertThat(sql.get(1)).isEqualTo(CREATE_LIKE);
        // 先把目标分区挪到暂存表（元数据操作），装载新数据后再整体换回，避免"已删未插"的空窗
        assertThat(sql.get(2)).contains("MOVE PARTITION ID '202609'").contains("TO TABLE");
        assertThat(sql.get(3)).contains("INSERT INTO").contains("SELECT * FROM s3(");
        assertThat(sql.get(4)).contains("DROP PARTITION ID '202609'");
        assertThat(sql.get(5)).contains("ATTACH PARTITION ID '202609'").contains("FROM");
        assertThat(sql.get(6)).contains("DROP TABLE IF EXISTS");
    }

    @Test
    void clusteredStatementsCarryOnCluster() {
        List<String> sql = new OfflineSqlRunner("tmdb_cluster").replacePartition(
                "dws", "dws_movie_metric_1d", "2026-09-30", "_stg", CREATE_LIKE);

        assertThat(sql).allSatisfy(statement -> assertThat(statement)
                .as("集群模式下所有 DDL 都必须带 ON CLUSTER")
                .contains("ON CLUSTER tmdb_cluster"));
    }

    @Test
    void copyFallbackUsesReplacePartition() {
        List<String> sql = new OfflineSqlRunner("").replacePartitionViaCopy(
                "dws", "dws_movie_metric_1d", "2026-09-30", "_stg", CREATE_LIKE);

        assertThat(sql).hasSize(5);
        assertThat(sql.get(3)).contains("REPLACE PARTITION ID '202609'").contains("FROM");
    }

    @Test
    void deleteByDateCoversWholeDay() {
        List<String> sql = new OfflineSqlRunner("").deleteByDate("dwd", "dim_movie", "valid_from", "2026-09-30");

        assertThat(sql).hasSize(1);
        assertThat(sql.get(0)).contains("ALTER TABLE dwd.dim_movie DELETE WHERE valid_from")
                .contains("toDateTime('2026-09-30 00:00:00')")
                .contains("toDateTime('2026-09-30 23:59:59')");
    }

    @Test
    void sourcePointsAtLakeParquetForTheGivenDate() {
        String source = OfflineSqlRunner.sourceOf("http://minio:9000/tmdb-lake/warehouse",
                "dws.dws_movie_metric_1d", "2026-09-30");

        assertThat(source).contains("s3('http://minio:9000/tmdb-lake/warehouse/dws.dws_movie_metric_1d")
                .contains("/dt=2026-09-30/*.parquet'")
                .contains("'Parquet'");
    }

    @Test
    void insertFromSourceReferencesTheSameSource() {
        String insert = new OfflineSqlRunner("").insertFromSource("dws", "dws_movie_metric_1d", "2026-09-30");
        assertThat(insert).startsWith("INSERT INTO dws.dws_movie_metric_1d SELECT * FROM s3(");
    }

    @Test
    void rejectsNullArguments() {
        OfflineSqlRunner runner = new OfflineSqlRunner("");
        assertThatThrownBy(() -> runner.replacePartition(null, "t", "2026-09-30", "_s", CREATE_LIKE))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> OfflineSqlRunner.partitionId(null)).isInstanceOf(NullPointerException.class);
    }
}
