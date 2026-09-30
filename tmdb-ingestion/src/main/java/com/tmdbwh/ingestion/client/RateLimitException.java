package com.tmdbwh.ingestion.client;

import com.tmdbwh.common.util.RetryAfterHint;
import java.time.Duration;
import java.util.Optional;

/**
 * HTTP 429：被 TMDB 限流。
 *
 * <p>实现 {@link RetryAfterHint}，携带响应头 {@code Retry-After}（秒）。重试器会优先等待服务端建议的时长， 而不是继续用指数退避——这是遵守上游限流契约的关键。
 */
public class RateLimitException extends TmdbException implements RetryAfterHint {

    private static final long serialVersionUID = 1L;

    private final Duration retryAfter;

    public RateLimitException(String url, Duration retryAfter) {
        super("TMDB 限流（HTTP 429）", url);
        this.retryAfter = retryAfter == null ? null : retryAfter.isNegative() ? Duration.ZERO : retryAfter;
    }

    @Override
    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }

    /** 服务端建议等待时长；无建议时返回 empty。 */
    public Optional<Duration> getRetryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
