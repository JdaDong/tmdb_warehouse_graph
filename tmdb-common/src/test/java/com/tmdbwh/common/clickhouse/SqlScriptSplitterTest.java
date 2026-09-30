package com.tmdbwh.common.clickhouse;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SqlScriptSplitterTest {

    @Test
    void splitsOnTopLevelSemicolons() {
        List<String> stmts = SqlScriptSplitter.split("CREATE DATABASE IF NOT EXISTS ods;\n\nCREATE DATABASE dwd ;  ");
        assertThat(stmts).containsExactly("CREATE DATABASE IF NOT EXISTS ods", "CREATE DATABASE dwd");
    }

    @Test
    void ignoresSemicolonsInsideStringsAndIdentifiers() {
        String script = "INSERT INTO t VALUES ('a;b', \"c;d\");\n"
                + "SELECT `weird;col` FROM t;\n"
                + "SELECT 'it\\'s; fine', 'double '' quote; ok'";
        List<String> stmts = SqlScriptSplitter.split(script);

        assertThat(stmts).hasSize(3);
        assertThat(stmts.get(0)).isEqualTo("INSERT INTO t VALUES ('a;b', \"c;d\")");
        assertThat(stmts.get(1)).isEqualTo("SELECT `weird;col` FROM t");
        assertThat(stmts.get(2)).isEqualTo("SELECT 'it\\'s; fine', 'double '' quote; ok'");
    }

    @Test
    void ignoresSemicolonsInsideComments() {
        String script = "-- header; comment\n"
                + "CREATE TABLE a (x UInt8) ENGINE = Memory; /* block; comment */\n"
                + "/* multi\n line; */ SELECT 1; -- trailing;\n";
        List<String> stmts = SqlScriptSplitter.split(script);

        assertThat(stmts).hasSize(2);
        assertThat(stmts.get(0)).startsWith("-- header; comment").endsWith("ENGINE = Memory");
        assertThat(stmts.get(1)).contains("SELECT 1");
    }

    @Test
    void dropsCommentOnlyStatements() {
        assertThat(SqlScriptSplitter.split("-- only comment;\n/* and block */;;  ;")).isEmpty();
        assertThat(SqlScriptSplitter.split("")).isEmpty();
        assertThat(SqlScriptSplitter.split(null)).isEmpty();
    }

    @Test
    void unterminatedConstructsDoNotLoop() {
        assertThat(SqlScriptSplitter.split("SELECT 'unterminated; still")).hasSize(1);
        assertThat(SqlScriptSplitter.split("SELECT 1 /* unterminated")).hasSize(1);
        assertThat(SqlScriptSplitter.split("SELECT 1 -- no newline")).containsExactly("SELECT 1 -- no newline");
    }
}
