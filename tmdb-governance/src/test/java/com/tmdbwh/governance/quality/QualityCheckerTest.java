package com.tmdbwh.governance.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tmdbwh.common.clickhouse.ClickHouseClient;
import com.typesafe.config.ConfigFactory;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 质量检查：判定逻辑与容错。 */
class QualityCheckerTest {

    @SuppressWarnings("unchecked")
    private static ClickHouseClient clientReturning(Double value) {
        ClickHouseClient client = mock(ClickHouseClient.class);
        when(client.query(anyString(), any())).thenReturn(List.of(value));
        return client;
    }

    private static QualityRule rule(String hocon) {
        return QualityRule.from(ConfigFactory.parseString(hocon));
    }

    @Test
    void rowCountBelowMinimumFails() {
        QualityRule rule = rule("id=r type=ROW_COUNT table=t min=10 severity=BLOCKER");
        QualityChecker checker = new QualityChecker(clientReturning(5.0), List.of(rule), true);

        QualityResult result = checker.judge(rule, 5.0, Instant.now());

        assertThat(result.isPassed()).isFalse();
        assertThat(result.getMessage()).contains("行数");
        assertThat(result.getThresholdValue()).isEqualTo(10.0);
    }

    @Test
    void rowCountMeetingMinimumPasses() {
        QualityRule rule = rule("id=r type=ROW_COUNT table=t min=10 severity=BLOCKER");
        assertThat(new QualityChecker(clientReturning(10.0), List.of(rule), true)
                .judge(rule, 10.0, Instant.now()).isPassed()).isTrue();
    }

    @Test
    void duplicatesAreFailures() {
        QualityRule rule = rule("id=u type=UNIQUE table=t columns=[\"a\"] max-duplicates=0 severity=BLOCKER");
        assertThat(new QualityChecker(clientReturning(3.0), List.of(rule), true)
                .judge(rule, 3.0, Instant.now()).isPassed()).isFalse();
    }

    @Test
    void nullRatioAboveThresholdFails() {
        QualityRule rule = rule("id=n type=NOT_NULL table=t column=c max-null-ratio=0.01 severity=BLOCKER");
        assertThat(new QualityChecker(clientReturning(0.5), List.of(rule), true)
                .judge(rule, 0.5, Instant.now()).isPassed()).isFalse();
    }

    @Test
    void staleDataFailsFreshness() {
        QualityRule rule = rule("id=f type=FRESHNESS table=t column=dt max-lag-days=1 severity=BLOCKER");
        assertThat(new QualityChecker(clientReturning(3.0), List.of(rule), true)
                .judge(rule, 3.0, Instant.now()).isPassed()).isFalse();
    }

    @Test
    void orphanRecordsFailReferential() {
        QualityRule rule = rule("id=ref type=REFERENTIAL table=t column=c ref-table=p ref-column=c"
                + " max-orphans=0 severity=WARN");
        assertThat(new QualityChecker(clientReturning(7.0), List.of(rule), true)
                .judge(rule, 7.0, Instant.now()).isPassed()).isFalse();
    }

    @Test
    void missingMetricValueIsTreatedAsFailure() {
        QualityRule rule = rule("id=r type=ROW_COUNT table=t min=1 severity=WARN");
        QualityResult result = new QualityChecker(clientReturning(1.0), List.of(rule), true)
                .judge(rule, null, Instant.now());

        assertThat(result.isPassed()).isFalse();
        assertThat(result.getMessage()).contains("未获取到指标值");
    }

    @Test
    void queryFailureBecomesFailedResultInsteadOfException() {
        ClickHouseClient client = mock(ClickHouseClient.class);
        when(client.query(anyString(), any())).thenThrow(new IllegalStateException("connection refused"));
        QualityRule rule = rule("id=r type=ROW_COUNT table=t min=1 severity=WARN");
        QualityChecker checker = new QualityChecker(client, List.of(rule), true);

        // 单条规则查询失败不应让整轮检查停摆
        QualityChecker.QualityReport report = checker.run("2026-09-30");

        assertThat(report.total()).isEqualTo(1);
        assertThat(report.failures()).isEqualTo(1);
        assertThat(report.getResults().get(0).getMessage()).contains("规则执行失败");
    }

    @Test
    void blockerFailureBlocksWhenEnabled() {
        QualityRule rule = rule("id=r type=ROW_COUNT table=t min=10 severity=BLOCKER");
        QualityChecker checker = new QualityChecker(clientReturning(0.0), List.of(rule), true);

        QualityChecker.QualityReport report = checker.run("2026-09-30");

        assertThat(report.blockerFailures()).isEqualTo(1);
        assertThat(checker.shouldBlock(report)).isTrue();
    }

    @Test
    void blockerFailureDoesNotBlockWhenDisabled() {
        QualityRule rule = rule("id=r type=ROW_COUNT table=t min=10 severity=BLOCKER");
        QualityChecker checker = new QualityChecker(clientReturning(0.0), List.of(rule), false);

        assertThat(checker.shouldBlock(checker.run("2026-09-30"))).isFalse();
    }

    @Test
    void resultsArePersisted() {
        ClickHouseClient client = clientReturning(100.0);
        QualityRule rule = rule("id=r type=ROW_COUNT table=t min=1 severity=WARN");

        new QualityChecker(client, List.of(rule), false).run("2026-09-30");

        verify(client).batchInsert(anyString(), any(), any());
    }

    @Test
    void persistenceFailureDoesNotBreakTheRun() {
        ClickHouseClient client = clientReturning(100.0);
        when(client.batchInsert(anyString(), any(), any()))
                .thenThrow(new IllegalStateException("write refused"));
        QualityRule rule = rule("id=r type=ROW_COUNT table=t min=1 severity=WARN");

        QualityChecker.QualityReport report = new QualityChecker(client, List.of(rule), false).run("2026-09-30");

        assertThat(report.total()).isEqualTo(1);
    }

    @Test
    void emptyRulesProduceEmptyReport() {
        ClickHouseClient client = mock(ClickHouseClient.class);
        QualityChecker.QualityReport report = new QualityChecker(client, List.of(), true).run("2026-09-30");
        assertThat(report.total()).isZero();
        verify(client, never()).batchInsert(anyString(), any(), any());
    }

    @Test
    void resultRowMatchesTableColumns() {
        QualityResult result = new QualityResult("id", "dwd", "t", "c", QualityRule.Severity.WARN, true,
                1.0, 0.0, "ok", Instant.now());

        assertThat(result.toRow()).hasSize(QualityResult.columns().size());
        assertThat(QualityResult.columns()).contains("checked_at", "rule_id", "passed", "message");
    }
}
