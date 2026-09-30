package com.tmdbwh.common.exception;

import java.util.Collections;
import java.util.List;

/**
 * 配置非法或缺失必填项时抛出。
 *
 * <p>会一次性汇总所有校验错误（而不是遇到第一个就失败），方便运维一次修正全部配置。
 */
public class InvalidConfigurationException extends TmdbWhException {

    private static final long serialVersionUID = 1L;

    private final List<String> errors;

    public InvalidConfigurationException(List<String> errors) {
        super("配置校验失败: " + String.join("; ", errors));
        this.errors = Collections.unmodifiableList(errors);
    }

    public InvalidConfigurationException(String error) {
        this(Collections.singletonList(error));
    }

    /** 返回全部校验错误信息。 */
    public List<String> getErrors() {
        return errors;
    }
}
