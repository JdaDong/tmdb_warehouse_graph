package com.tmdbwh.governance.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.Test;

/** 质量规则：SQL 生成与阈值解析。 */
class QualityRuleTest {

    private static QualityRule rule(String hocon) {
        return QualityRule.from(ConfigFactory.parseString(hocon));
    }

    @Test
    void rowCountSqlLimitsPartition() {
        QualityRule rule = rule("id=r type=ROW_COUNT table=ods.ods_change_event min=1 severity=BLOCKER");

        assertThat(rule.toSql("2026-09-30")).isEqualTo(
                "SELECT count() AS metric FROM ods.ods_change_event WHERE dt = toDate('2026-09-30')");
        assertThat(rule.getDatabase()).isEqualTo("ods");
        assertThat(rule.getTableName()).isEqualTo("ods_change_event");
        assertThat(rule.getSeverity()).isEqualTo(QualityRule.Severity.BLOCKER);
    }

    @Test
    void uniqueSqlUsesCombinedKey() {
        QualityRule rule = rule("id=u type=UNIQUE table=dwd.dim_movie columns=[\"movie_id\", \"valid_from\"]"
                + " max-duplicates=0 severity=BLOCKER");

        assertThat(rule.toSql("2026-09-30"))
                .contains("count() - uniqExact(movie_id, valid_from)");
        assertThat(rule.getMax()).isZero();
    }

    @Test
    void notNullSqlComputesRatio() {
        QualityRule rule = rule("id=n type=NOT_NULL table=dwd.dim_movie column=title max-null-ratio=0.0"
                + " severity=BLOCKER");

        assertThat(rule.toSql("2026-09-30"))
                .contains("countIf(title IS NULL) / greatest(count(), 1)");
        // 空值比例阈值统一收敛到 max
        assertThat(rule.getMax()).isZero();
    }

    @Test
    void rangeSqlCoversBothBounds() {
        QualityRule rule = rule("id=g type=RANGE table=dwd.dim_movie column=popularity min=0 max=10000"
                + " severity=WARN");

        assertThat(rule.toSql("2026-09-30")).contains("popularity < 0.0 OR popularity > 10000.0");
    }

    @Test
    void rangeSqlSupportsSingleBound() {
        QualityRule lower = rule("id=g1 type=RANGE table=t column=c min=1 severity=WARN");
        QualityRule upper = rule("id=g2 type=RANGE table=t column=c max=9 severity=WARN");

        assertThat(lower.toSql("")).contains("c < 1.0").doesNotContain("OR");
        assertThat(upper.toSql("")).contains("c > 9.0").doesNotContain("OR");
    }

    @Test
    void freshnessSqlMeasuresLagInDays() {
        QualityRule rule = rule("id=f type=FRESHNESS table=dws.dws_movie_metric_1d column=dt"
                + " max-lag-days=1 severity=BLOCKER");

        assertThat(rule.toSql("2026-09-30")).contains("dateDiff('day', max(dt), today())");
        assertThat(rule.getMax()).isEqualTo(1.0);
    }

    @Test
    void referentialSqlFindsOrphans() {
        QualityRule rule = rule("id=ref type=REFERENTIAL table=dwd.fact_movie_credit column=movie_id"
                + " ref-table=dwd.dim_movie ref-column=movie_id max-orphans=0 severity=WARN");

        assertThat(rule.toSql("2026-09-30")).contains("LEFT JOIN").contains("parent.movie_id = 0");
    }

    @Test
    void severityDefaultsToWarn() {
        assertThat(rule("id=x type=ROW_COUNT table=t min=1").getSeverity())
                .isEqualTo(QualityRule.Severity.WARN);
    }

    @Test
    void unknownTypeIsRejected() {
        assertThatThrownBy(() -> rule("id=x type=NOPE table=t"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rulesAreIdentifiedById() {
        QualityRule a = rule("id=same type=ROW_COUNT table=t min=1");
        QualityRule b = rule("id=same type=ROW_COUNT table=t2 min=1");
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }
}
