package com.tmdbwh.common.exception;

/** JSON 序列化 / 反序列化失败。 */
public class JsonCodecException extends TmdbWhException {

    private static final long serialVersionUID = 1L;

    public JsonCodecException(String message, Throwable cause) {
        super(message, cause);
    }
}
