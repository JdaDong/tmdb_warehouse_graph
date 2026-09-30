package com.tmdbwh.governance.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tmdbwh.common.clickhouse.ClickHouseClient;
import com.typesafe.config.ConfigFactory;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 生命周期：策略校验、计划生成与 dry-run 语义。 */
class LifecycleManagerTest {

    private static LifecyclePolicy policy(String hocon) {
        return LifecyclePolicy.from(ConfigFactory.parseString(hocon));
    }

    @Test
    void policyRejectsNegativeDurations() {
        assertThatThrownBy(() -> policy("table=t ttl-days=-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void policyRejectsColdAfterTtl() {
        // 先过期再下沉到冷存储是自相矛盾的
        assertThatThrownBy(() -> policy("table=t ttl-days=30 cold-after-days=60"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("冷存储时间");
    }

    @Test
    void ttlStatementIncludesColdVolume() {
        LifecyclePolicy policy = policy("table=ods.ods_change_event ttl-days=90 cold-after-days=7"
                + " partition-days=90");
        LifecycleManager manager = new LifecycleManager(mock(ClickHouseClient.class), List.of(policy), true, "");

        List<LifecycleManager.Action> actions = manager.plan(LocalDate.of(2026, 9, 30));

        assertThat(actions).hasSize(2);
        assertThat(actions.get(0).getSql())
                .contains("MODIFY TTL dt + INTERVAL 90 DAY")
                .contains("INTERVAL 7 DAY TO VOLUME 'cold'");
        assertThat(actions.get(0).getType()).isEqualTo("SET_TTL");
    }

    @Test
    void partitionActionDropsMonthBeforeCutoff() {
        LifecyclePolicy policy = policy("table=t partition-days=30");
        LifecycleManager manager = new LifecycleManager(mock(ClickHouseClient.class), List.of(policy), true, "");

        List<LifecycleManager.Action> actions = manager.plan(LocalDate.of(2026, 9, 30));

        // 30 天前是 8-31，按月分区只能整月清理 -> 202608 及更早
        assertThat(actions).hasSize(1);
        assertThat(actions.get(0).getSql()).contains("DROP PARTITION ID '202608'");
    }

    @Test
    void zeroTtlMeansNoDeletion() {
        // 维度表不能自动删除：删了历史版本链就断了
        LifecyclePolicy policy = policy("table=dwd.dim_movie ttl-days=0 partition-days=0");
        LifecycleManager manager = new LifecycleManager(mock(ClickHouseClient.class), List.of(policy), true, "");

        assertThat(manager.plan(LocalDate.of(2026, 9, 30))).isEmpty();
    }

    @Test
    void dryRunDoesNotExecuteDdl() {
        ClickHouseClient client = mock(ClickHouseClient.class);
        when(client.batchInsert(anyString(), anyList(), anyList())).thenReturn(1L);
        LifecyclePolicy policy = policy("table=t ttl-days=30");
        LifecycleManager manager = new LifecycleManager(client, List.of(policy), true, "");

        List<String> log = manager.apply(LocalDate.of(2026, 9, 30));

        assertThat(log).hasSize(1);
        assertThat(log.get(0)).startsWith("SKIPPED");
        verify(client, never()).execute(anyString());
    }

    @Test
    void applyExecutesDdlAndRecords() {
        ClickHouseClient client = mock(ClickHouseClient.class);
        when(client.batchInsert(anyString(), anyList(), anyList())).thenReturn(1L);
        LifecyclePolicy policy = policy("table=t ttl-days=30");
        LifecycleManager manager = new LifecycleManager(client, List.of(policy), false, "");

        List<String> log = manager.apply(LocalDate.of(2026, 9, 30));

        assertThat(log.get(0)).startsWith("APPLIED");
        verify(client).execute(anyString());
    }

    @Test
    void failedDdlIsRecordedNotThrown() {
        ClickHouseClient client = mock(ClickHouseClient.class);
        when(client.batchInsert(anyString(), anyList(), anyList())).thenReturn(1L);
        org.mockito.Mockito.doThrow(new IllegalStateException("denied")).when(client).execute(anyString());
        LifecyclePolicy policy = policy("table=t ttl-days=30");
        LifecycleManager manager = new LifecycleManager(client, List.of(policy), false, "");

        List<String> log = manager.apply(LocalDate.of(2026, 9, 30));

        // 单表失败不应中断其他表的清理
        assertThat(log.get(0)).startsWith("FAILED");
    }

    @Test
    void clusterStatementsCarryOnCluster() {
        LifecyclePolicy policy = policy("table=t ttl-days=30");
        LifecycleManager manager = new LifecycleManager(mock(ClickHouseClient.class), List.of(policy), true,
                "tmdb_cluster");

        assertThat(manager.plan(LocalDate.of(2026, 9, 30)).get(0).getSql())
                .contains("ON CLUSTER tmdb_cluster");
    }

    @Test
    void partitionIdIsYearMonth() {
        assertThat(LifecycleManager.partitionId(LocalDate.of(2026, 9, 30))).isEqualTo("202609");
        assertThat(LifecycleManager.partitionId(LocalDate.of(2026, 1, 5))).isEqualTo("202601");
    }

    @Test
    void defaultIsDryRun() {
        assertThat(LifecycleManager.defaultDryRun()).isTrue();
    }
}
