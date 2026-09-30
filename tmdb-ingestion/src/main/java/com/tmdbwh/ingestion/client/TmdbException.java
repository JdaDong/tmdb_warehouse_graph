package com.tmdbwh.ingestion.client;

import com.tmdbwh.common.exception.TmdbWhException;

/** TMDB API 调用失败的基类。 */
public class TmdbException extends TmdbWhException {

    private static final long serialVersionUID = 1L;

    private final String url;

    public TmdbException(String message, String url) {
        super(message + " | url=" + com.tmdbwh.common.util.Masking.maskUrl(url));
        this.url = url;
    }

    public TmdbException(String message, String url, Throwable cause) {
        super(message + " | url=" + com.tmdbwh.common.util.Masking.maskUrl(url), cause);
        this.url = url;
    }

    /** 原始请求 URL（未脱敏，仅用于排查；对外输出请用 {@link #getMessage()}）。 */
    public String getUrl() {
        return url;
    }
}
