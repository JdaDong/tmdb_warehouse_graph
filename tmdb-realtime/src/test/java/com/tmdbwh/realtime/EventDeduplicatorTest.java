package com.tmdbwh.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** 去重视觉逻辑（状态 TTL 与判定规则）。 */
class EventDeduplicatorTest {

    @Test
    void emitsWhenNoPreviousHash() {
        // 首次出现（或状态已过期）：必须放行
        assertThat(EventDeduplicator.shouldEmit(null, "abc")).isTrue();
    }

    @Test
    void dropsWhenContentUnchanged() {
        assertThat(EventDeduplicator.shouldEmit("abc", "abc")).isFalse();
    }

    @Test
    void emitsWhenContentChanged() {
        assertThat(EventDeduplicator.shouldEmit("abc", "def")).isTrue();
    }

    @Test
    void emitsWhenUpstreamHasNoContentHash() {
        // 旧版本上游没有 contentHash：宁可重复也不能丢数据
        assertThat(EventDeduplicator.shouldEmit("abc", null)).isTrue();
        assertThat(EventDeduplicator.shouldEmit("abc", "")).isTrue();
    }

    @Test
    void stateTtlMustBePositive() {
        assertThatThrownBy(() -> new EventDeduplicator(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EventDeduplicator(Duration.ofDays(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EventDeduplicator(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stateTtlIsExposedForMonitoring() {
        assertThat(new EventDeduplicator(Duration.ofDays(7)).getStateTtlMillis())
                .isEqualTo(Duration.ofDays(7).toMillis());
    }

    @Test
    void duplicateSideOutputTagIsStable() {
        // 侧输出标签名变化会导致升级后算子状态不兼容
        assertThat(EventDeduplicator.DUPLICATE_TAG.getId()).isEqualTo("duplicate-event");
    }
}
