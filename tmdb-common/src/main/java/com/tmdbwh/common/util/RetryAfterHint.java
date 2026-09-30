package com.tmdbwh.common.util;

import java.time.Duration;
import java.util.Optional;

/**
 * 可由异常实现的"建议重试等待时间"提示。
 *
 * <p>例如 TMDB 返回 HTTP 429 时携带 {@code Retry-After} 头，对应异常实现本接口后， {@link Retry} 会优先使用服务端建议的等待时间而非指数退避计算值。
 */
public interface RetryAfterHint {

    /** 服务端建议的等待时长；为空表示无建议，走默认退避策略。 */
    Optional<Duration> retryAfter();
}
