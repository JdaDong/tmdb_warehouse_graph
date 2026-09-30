package com.tmdbwh.governance.migration;

import com.tmdbwh.common.exception.TmdbWhException;

/** 迁移过程中的致命错误（校验和不匹配、版本回退、脚本缺失等）。 */
public class MigrationException extends TmdbWhException {

    private static final long serialVersionUID = 1L;

    public MigrationException(String message) {
        super(message);
    }

    public MigrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
