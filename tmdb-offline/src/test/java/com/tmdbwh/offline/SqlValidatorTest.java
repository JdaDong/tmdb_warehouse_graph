package com.tmdbwh.offline;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SQL 资源文件的静态校验。
 *
 * <p>ClickHouse 专有语法无法在单元测试里执行（需要真实服务端），这里至少保证结构与约定正确。
 */
class SqlValidatorTest {

    private static String resource(String name) throws IOException {
        try (InputStream in = SqlValidatorTest.class.getClassLoader().getResourceAsStream(name)) {
            assertThat(in).as("资源存在: " + name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void bundledClickHouseScriptsPassValidation() throws IOException {
        for (String name : List.of("sql/clickhouse/10-dws-ads-sync.sql", "sql/clickhouse/20-dim-date.sql")) {
            SqlValidator.Result result = SqlValidator.validate(resource(name));
            assertThat(result.getProblems()).as(name + " -> " + result).isEmpty();
            assertThat(result.isValid()).isTrue();
        }
    }

    @Test
    void detectsUnbalancedParentheses() {
        SqlValidator.Result result = SqlValidator.validate("SELECT count( FROM t;");
        assertThat(result.isValid()).isFalse();
        assertThat(result.toString()).contains("括号不配对");
    }

    @Test
    void detectsUnclosedString() {
        SqlValidator.Result result = SqlValidator.validate("INSERT INTO t SELECT 'abc;");
        assertThat(result.isValid()).isFalse();
        assertThat(result.toString()).contains("单引号");
    }

    @Test
    void detectsSparkOnlyFunctions() {
        SqlValidator.Result result = SqlValidator.validate("SELECT from_json(payload) FROM t;");
        assertThat(result.isValid()).isFalse();
        assertThat(result.toString()).contains("Spark 专有函数");
    }

    @Test
    void rejectsEmptyContent() {
        assertThat(SqlValidator.validate("").isValid()).isFalse();
        assertThat(SqlValidator.validate("   \n ").isValid()).isFalse();
        assertThat(SqlValidator.validate(null).isValid()).isFalse();
    }

    @Test
    void splitsStatementsIgnoringSemicolonsInStringsAndComments() {
        List<String> statements = SqlValidator.splitStatements(
                "-- 注释里的 ; 不算\nSELECT 'a;b' AS x;\nSELECT 2;");
        assertThat(statements).hasSize(3);
        assertThat(statements.get(1)).contains("SELECT 'a;b'");
    }

    @Test
    void allowsInsertAndAlterStatements() {
        assertThat(SqlValidator.validate("INSERT INTO dwd.t SELECT * FROM s3('x');").isValid()).isTrue();
        assertThat(SqlValidator.validate("ALTER TABLE dwd.t DROP PARTITION ID '202609';").isValid()).isTrue();
    }
}
