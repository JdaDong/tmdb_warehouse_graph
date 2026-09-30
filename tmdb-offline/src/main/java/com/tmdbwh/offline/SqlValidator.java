package com.tmdbwh.offline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * SQL 文件的静态校验（不连接数据库）。
 *
 * <p>目的：ClickHouse 专有语法无法在本项目的单元测试里执行（需要真实服务端），
 * 因此至少把"能静态发现的错误"拦在构建阶段：括号不配对、引号不闭合、语句缺分号、
 * 误用了 Spark 专有函数、占位符写错等。
 *
 * <p>这不是语法解析器，只做结构与约定检查；真正的语法正确性仍由集成测试保证。
 */
public final class SqlValidator {

    /** 允许出现在 ClickHouse SQL 中的语句开头。 */
    private static final List<String> ALLOWED_STARTS = Arrays.asList(
            "INSERT", "ALTER", "SELECT", "CREATE", "DROP", "TRUNCATE", "OPTIMIZE", "SYSTEM", "CALL", "--");

    /** Spark 专有函数（出现在 ClickHouse 脚本里说明复制错了文件）。 */
    private static final List<String> SPARK_ONLY = Arrays.asList(
            "from_json(", "explode(", "get_json_object(", "to_json(", "named_struct(");

    private SqlValidator() {}

    /** 校验结果。 */
    public static final class Result {
        private final List<String> problems = new ArrayList<>();

        void add(String problem) {
            problems.add(problem);
        }

        public boolean isValid() {
            return problems.isEmpty();
        }

        public List<String> getProblems() {
            return List.copyOf(problems);
        }

        @Override
        public String toString() {
            return isValid() ? "OK" : String.join("; ", problems);
        }
    }

    /** 校验一段 SQL 文本。 */
    public static Result validate(String sql) {
        Result result = new Result();
        if (sql == null || sql.trim().isEmpty()) {
            result.add("SQL 内容为空");
            return result;
        }
        checkBalance(result, sql, '(', ')');
        checkBalance(result, sql, '{', '}');
        long quotes = sql.chars().filter(c -> c == '\'').count();
        if (quotes % 2 != 0) {
            result.add("单引号数量为奇数（可能有未闭合的字符串）");
        }
        for (String statement : splitStatements(sql)) {
            String trimmed = statement.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String upper = trimmed.toUpperCase(Locale.ROOT);
            boolean allowed = false;
            for (String prefix : ALLOWED_STARTS) {
                if (upper.startsWith(prefix)) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                result.add("语句开头不在允许列表内: " + firstLine(trimmed));
            }
            if (upper.contains("${") && !upper.contains("}")) {
                result.add("占位符未闭合: " + firstLine(trimmed));
            }
            for (String fn : SPARK_ONLY) {
                if (upper.contains(fn.toUpperCase(Locale.ROOT))) {
                    result.add("出现 Spark 专有函数 " + fn + ": " + firstLine(trimmed));
                }
            }
        }
        return result;
    }

    /** 按分号切分语句（忽略注释与字符串内的分号）。 */
    public static List<String> splitStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inLineComment = false;
        boolean inString = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                }
                current.append(c);
                continue;
            }
            if (inString) {
                current.append(c);
                if (c == '\'' && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    current.append('\'');
                    i++;
                } else if (c == '\'') {
                    inString = false;
                }
                continue;
            }
            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                inLineComment = true;
                current.append("--");
                i++;
                continue;
            }
            if (c == '\'') {
                inString = true;
                current.append(c);
                continue;
            }
            if (c == ';') {
                statements.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        statements.add(current.toString());
        return statements;
    }

    private static void checkBalance(Result result, String sql, char open, char close) {
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                // ClickHouse 的转义是双写单引号（''），不是反斜杠
                if (inString && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    i++;
                    continue;
                }
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth < 0) {
                    result.add("多余的 " + close);
                    return;
                }
            }
        }
        if (depth != 0) {
            result.add("括号不配对: " + open + close + " 差值 " + depth);
        }
    }

    private static String firstLine(String text) {
        int newline = text.indexOf('\n');
        String line = newline < 0 ? text : text.substring(0, newline);
        return line.length() > 60 ? line.substring(0, 60) + "..." : line;
    }
}
