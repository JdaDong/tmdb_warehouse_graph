package com.tmdbwh.common.exception;

/** 重试次数耗尽后仍失败；{@link #getCause()} 为最后一次失败的原始异常。 */
public class RetryExhaustedException extends TmdbWhException {

    private static final long serialVersionUID = 1L;

    private final int attempts;

    public RetryExhaustedException(String operation, int attempts, Throwable lastFailure) {
        super(String.format("操作 [%s] 重试 %d 次后仍失败: %s", operation, attempts, lastFailure), lastFailure);
        this.attempts = attempts;
    }

    /** 实际执行的总次数（含首次）。 */
    public int getAttempts() {
        return attempts;
    }
}
