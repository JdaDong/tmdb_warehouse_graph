package com.tmdbwh.ingestion.client;

/** HTTP 5xx：服务端暂时不可用，可重试。 */
public class TmdbServerException extends TmdbException {

    private static final long serialVersionUID = 1L;

    private final int status;

    public TmdbServerException(int status, String url, String body) {
        super("TMDB 服务端错误（HTTP " + status + "）: " + abbreviate(body), url);
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
