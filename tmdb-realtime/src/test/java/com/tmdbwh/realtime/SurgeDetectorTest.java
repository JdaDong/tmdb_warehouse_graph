package com.tmdbwh.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 飙升判定：阈值、样本量与基线下限三重条件。 */
class SurgeDetectorTest {

    private final SurgeDetector detector = new SurgeDetector(1.5, 3, 20.0);

    @Test
    void triggersWhenRatioExceedsThreshold() {
        assertThat(detector.isSurge(60.0, 30.0, 5)).isTrue();
    }

    @Test
    void doesNotTriggerJustBelowThreshold() {
        assertThat(detector.isSurge(44.0, 30.0, 5)).isFalse();
    }

    @Test
    void requiresMinimumSamples() {
        // 样本太少时均值不可信：即使涨幅很大也不告警
        assertThat(detector.isSurge(300.0, 30.0, 2)).isFalse();
        assertThat(detector.isSurge(300.0, 30.0, 3)).isTrue();
    }

    @Test
    void requiresMinimumBaseline() {
        // 冷门内容从 0.5 涨到 5 是 10 倍，但没有业务价值
        assertThat(detector.isSurge(5.0, 0.5, 10)).isFalse();
        assertThat(detector.isSurge(60.0, 30.0, 10)).isTrue();
    }

    @Test
    void firstObservationHasNoBaseline() {
        assertThat(detector.isSurge(100.0, 0.0, 10)).isFalse();
    }

    @Test
    void growthRatioIsZeroWhenBaselineMissing() {
        // Infinity 会污染下游聚合（sum/avg 都变成 Infinity），必须兜底为 0
        assertThat(SurgeDetector.growthRatio(100.0, 0.0)).isEqualTo(0.0);
        assertThat(SurgeDetector.growthRatio(100.0, 50.0)).isEqualTo(2.0);
    }

    @Test
    void alertIdIsStableForReplay() {
        // 重放 / 重算时 ID 必须一致，否则 ReplacingMergeTree 去重失效、告警重复
        assertThat(SurgeDetector.alertId("movie", 27205L, 1_700_000_000_000L))
                .isEqualTo("movie:27205:1700000000000");
        assertThat(SurgeDetector.alertId("movie", 27205L, 1_700_000_000_000L))
                .isEqualTo(SurgeDetector.alertId("movie", 27205L, 1_700_000_000_000L));
    }

    @Test
    void rejectsInvalidParameters() {
        assertThatThrownBy(() -> new SurgeDetector(1.0, 3, 20.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SurgeDetector(1.5, 0, 20.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SurgeDetector(1.5, 3, -1.0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void configIsExposed() {
        assertThat(detector.getRatioThreshold()).isEqualTo(1.5);
        assertThat(detector.getMinSamples()).isEqualTo(3);
        assertThat(detector.getMinBaseline()).isEqualTo(20.0);
    }
}
