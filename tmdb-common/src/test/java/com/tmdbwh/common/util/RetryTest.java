package com.tmdbwh.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tmdbwh.common.exception.RetryExhaustedException;
import com.tmdbwh.common.exception.TmdbWhException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RetryTest {

    private final List<Duration> sleeps = new ArrayList<>();

    private Retry.Builder base() {
        return Retry.builder("test")
                .initialDelay(Duration.ofMillis(100))
                .maxDelay(Duration.ofSeconds(1))
                .multiplier(2.0)
                .jitterRatio(0)
                .sleeper(sleeps::add);
    }

    /** 模拟 429 响应的异常。 */
    static final class TooManyRequests extends RuntimeException implements RetryAfterHint {
        private static final long serialVersionUID = 1L;
        private final Duration after;

        TooManyRequests(Duration after) {
            super("429");
            this.after = after;
        }

        @Override
        public Optional<Duration> retryAfter() {
            return Optional.ofNullable(after);
        }
    }

    @Test
    void succeedsAfterTransientFailuresWithExponentialBackoff() {
        AtomicInteger calls = new AtomicInteger();
        String result = base().maxAttempts(4).build().call(() -> {
            if (calls.incrementAndGet() < 4) {
                throw new IOException("boom");
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(4);
        assertThat(sleeps).containsExactly(Duration.ofMillis(100), Duration.ofMillis(200), Duration.ofMillis(400));
    }

    @Test
    void backoffIsCappedByMaxDelay() {
        Retry retry = base().maxAttempts(10).build();
        assertThat(retry.backoff(1)).isEqualTo(Duration.ofMillis(100));
        assertThat(retry.backoff(4)).isEqualTo(Duration.ofMillis(800));
        assertThat(retry.backoff(5)).isEqualTo(Duration.ofSeconds(1));
        assertThat(retry.backoff(30)).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void jitterStaysWithinBounds() {
        Retry retry = base().jitterRatio(0.5).random(new Random(42)).build();
        for (int i = 0; i < 200; i++) {
            long ms = retry.backoff(2).toMillis();
            assertThat(ms).isBetween(100L, 300L);
        }
    }

    @Test
    void exhaustedAttemptsThrowWithLastCause() {
        AtomicInteger calls = new AtomicInteger();
        Retry retry = base().maxAttempts(3).build();

        assertThatThrownBy(() -> retry.run(() -> {
            calls.incrementAndGet();
            throw new IllegalStateException("still failing #" + calls.get());
        }))
                .isInstanceOf(RetryExhaustedException.class)
                .hasMessageContaining("[test]")
                .hasRootCauseMessage("still failing #3")
                .satisfies(e -> assertThat(((RetryExhaustedException) e).getAttempts()).isEqualTo(3));
        assertThat(calls).hasValue(3);
        assertThat(sleeps).hasSize(2);
    }

    @Test
    void nonRetryableFailsImmediately() {
        AtomicInteger calls = new AtomicInteger();
        Retry retry = base().maxAttempts(5).retryOn(e -> e instanceof IOException).build();

        assertThatThrownBy(() -> retry.run(() -> {
            calls.incrementAndGet();
            throw new IllegalArgumentException("bad request");
        })).isExactlyInstanceOf(IllegalArgumentException.class);
        assertThat(calls).hasValue(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void nonRetryableCheckedExceptionsAreWrapped() {
        Retry retry = base().retryOn(e -> false).build();

        assertThatThrownBy(() -> retry.run(() -> {
            throw new IOException("io");
        })).isInstanceOf(UncheckedIOException.class);
        assertThatThrownBy(() -> retry.run(() -> {
            throw new Exception("checked");
        })).isInstanceOf(TmdbWhException.class).hasMessage("checked");
    }

    @Test
    void retryAfterHintOverridesBackoff() {
        AtomicInteger calls = new AtomicInteger();
        base().maxAttempts(3).build().call(() -> {
            if (calls.incrementAndGet() == 1) {
                throw new TooManyRequests(Duration.ofSeconds(7));
            }
            if (calls.get() == 2) {
                throw new TooManyRequests(null);
            }
            return 1;
        });

        // 服务端建议 7s 不受 maxDelay(1s) 限制；无建议时回落到指数退避（第 2 次 = 200ms）
        assertThat(sleeps).containsExactly(Duration.ofSeconds(7), Duration.ofMillis(200));
    }

    @Test
    void negativeRetryAfterTreatedAsZeroAndNotSlept() {
        AtomicInteger calls = new AtomicInteger();
        base().maxAttempts(2).build().call(() -> {
            if (calls.incrementAndGet() == 1) {
                throw new TooManyRequests(Duration.ofSeconds(-3));
            }
            return 1;
        });
        assertThat(sleeps).isEmpty();
    }

    @Test
    void interruptionDuringSleepRestoresFlag() {
        Retry retry = base().maxAttempts(3).sleeper(d -> {
            throw new InterruptedException();
        }).build();

        try {
            assertThatThrownBy(() -> retry.run(() -> {
                throw new IOException("x");
            })).isInstanceOf(TmdbWhException.class).hasMessageContaining("中断");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void interruptedActionIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        try {
            assertThatThrownBy(() -> base().maxAttempts(5).build().run(() -> {
                calls.incrementAndGet();
                throw new InterruptedException();
            })).isInstanceOf(TmdbWhException.class);
            assertThat(calls).hasValue(1);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void builderValidatesArguments() {
        assertThatThrownBy(() -> Retry.builder("x").maxAttempts(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Retry.builder("x").multiplier(0.5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Retry.builder("x").jitterRatio(1.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Retry.builder("x").initialDelay(Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Retry.builder("x").initialDelay(Duration.ofSeconds(5))
                .maxDelay(Duration.ofSeconds(1)).build()).isInstanceOf(IllegalArgumentException.class);
        assertThat(Retry.builder("x").maxAttempts(7).build().getMaxAttempts()).isEqualTo(7);
    }
}
