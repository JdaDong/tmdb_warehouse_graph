package com.tmdbwh.ingestion.client;

import java.io.IOException;

/** 网络 / IO 失败（连接超时、读超时、连接重置等），可重试。 */
public class TmdbIOException extends TmdbException {

    private static final long serialVersionUID = 1L;

    public TmdbIOException(String url, IOException cause) {
        super("TMDB 请求 IO 失败: " + cause, url, cause);
    }
}
