package com.tmdbwh.common.exception;

/** 对象存储（MinIO / S3）读写失败。 */
public class StorageException extends TmdbWhException {

    private static final long serialVersionUID = 1L;

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }

    public StorageException(String message) {
        super(message);
    }
}
