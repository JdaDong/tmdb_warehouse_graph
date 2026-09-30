package com.tmdbwh.common.exception;

/**
 * ClickHouse 执行失败。
 *
 * <p>消息中附带截断后的 SQL（最多 {@value #MAX_SQL_LENGTH} 字符），便于定位问题又不至于刷屏。
 */
public class ClickHouseException extends TmdbWhException {

    private static final long serialVersionUID = 1L;

    /** 消息中 SQL 的最大保留长度。 */
    public static final int MAX_SQL_LENGTH = 500;

    public ClickHouseException(String message, String sql, Throwable cause) {
        super(message + " | sql=" + abbreviate(sql), cause);
    }

    public ClickHouseException(String message) {
        super(message);
    }

    static String abbreviate(String sql) {
        if (sql == null) {
            return "<null>";
        }
        String oneLine = sql.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= MAX_SQL_LENGTH ? oneLine : oneLine.substring(0, MAX_SQL_LENGTH) + "...";
    }
}
