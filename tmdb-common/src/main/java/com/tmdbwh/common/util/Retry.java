package com.tmdbwh.common.util;

import com.tmdbwh.common.exception.RetryExhaustedException;
import com.tmdbwh.common.exception.TmdbWhException;
import java.io.IOException;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 通用重试器：指数退避 + 随机抖动 + 服务端 Retry-After 提示。
 *
 * <p>用于对象存储、ClickHouse、Neo4j 等"偶发失败可自愈"的外部调用。线程安全，可在多个线程间复用同一实例。
 *
 * <pre>{@code
 * Retry retry = Retry.builder("ch-insert")
 *         .maxAttempts(5)
 *         .initialDelay(Duration.ofMillis(200))
 *         .retryOn(e -> e instanceof SQLException)
 *         .build();
 * retry.run(() -> client.execute(sql));
 * }</pre>
 *
 * <p>语义说明：
 *
 * <ul>
 *   <li>第 n 次重试前等待 {@code min(maxDelay, initialDelay * multiplier^(n-1))}，再施加 ±jitter 比例的随机抖动；
 *   <li>若异常实现 {@link RetryAfterHint} 且给出建议值，则等待 {@code max(建议值, 0)}（不受 maxDelay 约束，尊重服务端限流）；
 *   <li>不满足 {@code retryOn} 的异常立即抛出：运行时异常原样抛出，受检异常包装为 {@link TmdbWhException}；
 *   <li>达到 {@code maxAttempts} 后抛出 {@link RetryExhaustedException}，cause 为最后一次异常。
 * </ul>
 */
public final class Retry {

    private static final Logger LOG = LoggerFactory.getLogger(Retry.class);

    /** 可抛出受检异常的有返回值操作。 */
    @FunctionalInterface
    public interface CheckedSupplier<T> {
        /** 执行操作。 */
        T get() throws Exception;
    }

    /** 可抛出受检异常的无返回值操作。 */
    @FunctionalInterface
    public interface CheckedRunnable {
        /** 执行操作。 */
        void run() throws Exception;
    }

    /** 休眠抽象，便于单元测试中替换为不真正休眠的实现。 */
    @FunctionalInterface
    public interface Sleeper extends Serializable {
        /** 休眠指定时长。 */
        void sleep(Duration duration) throws InterruptedException;
    }

    private final String name;
    private final int maxAttempts;
    private final Duration initialDelay;
    private final Duration maxDelay;
    private final double multiplier;
    private final double jitterRatio;
    private final Predicate<Throwable> retryOn;
    private final Sleeper sleeper;
    private final Random random;

    private Retry(Builder b) {
        this.name = b.name;
        this.maxAttempts = b.maxAttempts;
        this.initialDelay = b.initialDelay;
        this.maxDelay = b.maxDelay;
        this.multiplier = b.multiplier;
        this.jitterRatio = b.jitterRatio;
        this.retryOn = b.retryOn;
        this.sleeper = b.sleeper;
        this.random = b.random;
    }

    /**
     * 创建构建器。
     *
     * @param name 操作名，用于日志与异常信息
     */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    /**
     * 执行带返回值的操作。
     *
     * @throws RetryExhaustedException 重试耗尽
     */
    public <T> T call(CheckedSupplier<T> action) {
        Objects.requireNonNull(action, "action");
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                return action.get();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new TmdbWhException("操作 [" + name + "] 被中断", ie);
            } catch (Exception e) {
                if (!retryOn.test(e)) {
                    throw propagate(e);
                }
                if (attempt >= maxAttempts) {
                    throw new RetryExhaustedException(name, attempt, e);
                }
                Duration wait = delayFor(attempt, e);
                LOG.warn("操作 [{}] 第 {}/{} 次失败，{} ms 后重试: {}",
                        name, attempt, maxAttempts, wait.toMillis(), Masking.maskText(String.valueOf(e)));
                sleepQuietly(wait);
            }
        }
    }

    /**
     * 执行无返回值的操作。
     *
     * @throws RetryExhaustedException 重试耗尽
     */
    public void run(CheckedRunnable action) {
        Objects.requireNonNull(action, "action");
        call(() -> {
            action.run();
            return null;
        });
    }

    /** 计算第 {@code attempt} 次失败后的等待时长（attempt 从 1 开始）。 */
    Duration delayFor(int attempt, Throwable failure) {
        if (failure instanceof RetryAfterHint) {
            Optional<Duration> hint = ((RetryAfterHint) failure).retryAfter();
            if (hint.isPresent()) {
                return hint.get().isNegative() ? Duration.ZERO : hint.get();
            }
        }
        return backoff(attempt);
    }

    /** 纯指数退避（含抖动）计算。 */
    Duration backoff(int attempt) {
        double base = initialDelay.toMillis() * Math.pow(multiplier, Math.max(0, attempt - 1));
        double capped = Math.min(base, maxDelay.toMillis());
        double jitter = jitterRatio <= 0 ? 0 : capped * jitterRatio * (random.nextDouble() * 2 - 1);
        long millis = Math.max(0L, Math.round(capped + jitter));
        return Duration.ofMillis(Math.min(millis, maxDelay.toMillis()));
    }

    private void sleepQuietly(Duration wait) {
        if (wait.isZero()) {
            return;
        }
        try {
            sleeper.sleep(wait);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new TmdbWhException("操作 [" + name + "] 等待重试时被中断", ie);
        }
    }

    private static RuntimeException propagate(Exception e) {
        if (e instanceof RuntimeException) {
            return (RuntimeException) e;
        }
        if (e instanceof IOException) {
            return new UncheckedIOException((IOException) e);
        }
        return new TmdbWhException(e.getMessage(), e);
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    /** {@link Retry} 构建器；所有参数均有生产可用的默认值。 */
    public static final class Builder {
        private final String name;
        private int maxAttempts = 3;
        private Duration initialDelay = Duration.ofMillis(200);
        private Duration maxDelay = Duration.ofSeconds(30);
        private double multiplier = 2.0;
        private double jitterRatio = 0.2;
        private Predicate<Throwable> retryOn = e -> true;
        private Sleeper sleeper = d -> Thread.sleep(d.toMillis());
        private Random random = new Random();

        private Builder(String name) {
            this.name = Objects.requireNonNull(name, "name");
        }

        /** 最大执行次数（含首次），必须 ≥ 1。 */
        public Builder maxAttempts(int value) {
            if (value < 1) {
                throw new IllegalArgumentException("maxAttempts 必须 >= 1");
            }
            this.maxAttempts = value;
            return this;
        }

        /** 首次重试前的等待时长。 */
        public Builder initialDelay(Duration value) {
            this.initialDelay = requireNonNegative(value, "initialDelay");
            return this;
        }

        /** 单次等待上限。 */
        public Builder maxDelay(Duration value) {
            this.maxDelay = requireNonNegative(value, "maxDelay");
            return this;
        }

        /** 退避倍数，必须 ≥ 1。 */
        public Builder multiplier(double value) {
            if (value < 1.0) {
                throw new IllegalArgumentException("multiplier 必须 >= 1");
            }
            this.multiplier = value;
            return this;
        }

        /** 抖动比例，取值 [0, 1)。 */
        public Builder jitterRatio(double value) {
            if (value < 0 || value >= 1) {
                throw new IllegalArgumentException("jitterRatio 取值范围 [0, 1)");
            }
            this.jitterRatio = value;
            return this;
        }

        /** 判定异常是否可重试。 */
        public Builder retryOn(Predicate<Throwable> value) {
            this.retryOn = Objects.requireNonNull(value, "retryOn");
            return this;
        }

        /** 自定义休眠实现（测试用）。 */
        public Builder sleeper(Sleeper value) {
            this.sleeper = Objects.requireNonNull(value, "sleeper");
            return this;
        }

        /** 自定义随机源（测试用，保证抖动可复现）。 */
        public Builder random(Random value) {
            this.random = Objects.requireNonNull(value, "random");
            return this;
        }

        /** 构建不可变的重试器。 */
        public Retry build() {
            if (maxDelay.compareTo(initialDelay) < 0) {
                throw new IllegalArgumentException("maxDelay 不能小于 initialDelay");
            }
            return new Retry(this);
        }

        private static Duration requireNonNegative(Duration d, String field) {
            Objects.requireNonNull(d, field);
            if (d.isNegative()) {
                throw new IllegalArgumentException(field + " 不能为负");
            }
            return d;
        }
    }
}
