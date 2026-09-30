package com.tmdbwh.common.exception;

/**
 * 平台统一的运行时异常基类。
 *
 * <p>所有模块自定义异常均继承本类，便于在 CLI 入口 / 调度层统一捕获、打印摘要并映射退出码。 异常消息中禁止携带密钥或完整响应体，需要时请先经过 {@link
 * com.tmdbwh.common.util.Masking} 脱敏。
 */
public class TmdbWhException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TmdbWhException(String message) {
        super(message);
    }

    public TmdbWhException(String message, Throwable cause) {
        super(message, cause);
    }
}
