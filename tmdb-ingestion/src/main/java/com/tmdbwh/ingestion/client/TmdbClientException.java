package com.tmdbwh.ingestion.client;

/** HTTP 4xx（除 429）：请求本身有问题，重试无意义，立即失败。 */
public class TmdbClientException extends TmdbException {

    private static final long serialVersionUID = 1L;

    private final int status;

    public TmdbClientException(int status, String url, String body) {
        super("TMDB 请求被拒绝（HTTP " + status + "）: " + abbreviate(body), url);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }

    private static String abbreviate(String body) {
        if (body == null || body.isEmpty()) {
            return "<空响应>";
        }
        String oneLine = body.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 200 ? oneLine : oneLine.substring(0, 200) + "...";
    }
}
