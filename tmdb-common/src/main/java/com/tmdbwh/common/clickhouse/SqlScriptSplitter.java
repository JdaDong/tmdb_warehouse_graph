package com.tmdbwh.common.clickhouse;

import java.util.ArrayList;
import java.util.List;

/**
 * 多语句 SQL 脚本拆分器。
 *
 * <p>ClickHouse JDBC 不支持单次执行多条语句，迁移脚本 / 初始化脚本需先拆分。按分号拆分时正确处理：
 *
 * <ul>
 *   <li>单引号 / 双引号 / 反引号字符串中的分号（支持 {@code \'} 与 {@code ''} 两种转义）；
 *   <li>{@code --} 行注释与 {@code /* *}{@code /} 块注释中的分号；
 *   <li>只包含注释或空白的"语句"会被丢弃。
 * </ul>
 */
public final class SqlScriptSplitter {

    private SqlScriptSplitter() {}

    /**
     * 拆分脚本。
     *
     * @param script SQL 脚本
     * @return 去除首尾空白后的语句列表（不含末尾分号）
     */
    public static List<String> split(String script) {
        List<String> statements = new ArrayList<>();
        if (script == null || script.isEmpty()) {
            return statements;
        }
        StringBuilder current = new StringBuilder();
        boolean hasCode = false;
        int n = script.length();
        int i = 0;
        while (i < n) {
            char c = script.charAt(i);
            char next = i + 1 < n ? script.charAt(i + 1) : '\0';
            if (c == '-' && next == '-') {
                int end = script.indexOf('\n', i);
                end = end < 0 ? n : end;
                current.append(script, i, end);
                i = end;
            } else if (c == '/' && next == '*') {
                int end = script.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                current.append(script, i, end);
                i = end;
            } else if (c == '\'' || c == '"' || c == '`') {
                int end = skipQuoted(script, i, c);
                current.append(script, i, end);
                hasCode = true;
                i = end;
            } else if (c == ';') {
                flush(statements, current, hasCode);
                current.setLength(0);
                hasCode = false;
                i++;
            } else {
                if (!Character.isWhitespace(c)) {
                    hasCode = true;
                }
                current.append(c);
                i++;
            }
        }
        flush(statements, current, hasCode);
        return statements;
    }

    /** 返回引号字符串结束位置（不含），未闭合则到脚本末尾。 */
    private static int skipQuoted(String s, int start, char quote) {
        int i = start + 1;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == quote) {
                if (i + 1 < n && s.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return n;
    }

    private static void flush(List<String> statements, StringBuilder current, boolean hasCode) {
        if (hasCode) {
            String stmt = current.toString().trim();
            if (!stmt.isEmpty()) {
                statements.add(stmt);
            }
        }
    }
}
