package com.tmdbwh.offline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Iceberg 维护语句的生成（执行部分需要真实 Catalog，由集成测试覆盖）。 */
class IcebergMaintenanceTest {

    @Test
    void expireSnapshotsKeepsRecentOnes() {
        String sql = IcebergMaintenance.expireSnapshots("dwd.fact_movie_credit", 7);

        assertThat(sql).startsWith("CALL lake.system.expire_snapshots(");
        assertThat(sql).contains("table => 'dwd.fact_movie_credit'");
        // 保留最近 3 个快照，避免把刚写入的也清掉导致查询失败
        assertThat(sql).contains("retain_last => 3");
    }

    @Test
    void removeOrphanFilesSupportsDryRun() {
        assertThat(IcebergMaintenance.removeOrphanFiles("dwd.t", true)).contains("dry_run => true");
        assertThat(IcebergMaintenance.removeOrphanFiles("dwd.t", false)).contains("dry_run => false");
    }

    @Test
    void rewriteTargets128MbFiles() {
        String sql = IcebergMaintenance.rewriteDataFiles("dwd.fact_movie_credit");
        assertThat(sql).contains("strategy => 'binpack'").contains("134217728");
    }

    @Test
    void dryRunNeverCompactsBecauseItWouldWrite() {
        // 小文件合并是写操作，dry-run 必须跳过而不是"顺便执行"
        java.util.List<String> output = IcebergMaintenance.run((org.apache.spark.sql.SparkSession) null, "dwd.t", true, true, true, true);
        assertThat(output).allSatisfy(line -> assertThat(line).startsWith("[dry-run]"));
        assertThat(output).noneMatch(line -> line.contains("rewrite_data_files -> "));
    }

    @Test
    void rejectsUnsafeTableNames() {
        assertThatThrownBy(() -> IcebergMaintenance.expireSnapshots("dwd.t; DROP TABLE x", 7))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IcebergMaintenance.expireSnapshots("dwd", 7))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IcebergMaintenance.expireSnapshots("dwd.t", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
