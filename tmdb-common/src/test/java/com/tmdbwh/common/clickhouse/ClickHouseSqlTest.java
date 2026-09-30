package com.tmdbwh.common.clickhouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ClickHouseSqlTest {

    @Test
    void validIdentifiersAndTables() {
        assertThat(ClickHouseSql.table("dwd.dim_movie")).isEqualTo("dwd.dim_movie");
        assertThat(ClickHouseSql.table("dim_movie")).isEqualTo("dim_movie");
        assertThat(ClickHouseSql.identifier("_version")).isEqualTo("_version");
    }

    @ParameterizedTest
    @ValueSource(strings = {"dwd.dim_movie; DROP TABLE x", "a.b.c", "1abc", "db.", ".tbl", "tbl-name", "t`x"})
    void rejectsInjectionInTableNames(String name) {
        assertThatThrownBy(() -> ClickHouseSql.table(name)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"202609", "'2026-09-30'", "tuple()", "ID '202609_1_1_0'", "(202609, 'movie')", "(1,2)"})
    void acceptsWhitelistedPartitions(String expr) {
        assertThat(ClickHouseSql.partition(expr)).isEqualTo(expr);
    }

    @ParameterizedTest
    @ValueSource(strings = {"'2026-09-30' FROM x", "202609 OR 1=1", "'a'';DROP'", "toYYYYMM(now())", ""})
    void rejectsNonWhitelistedPartitions(String expr) {
        assertThatThrownBy(() -> ClickHouseSql.partition(expr)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void partitionLiterals() {
        LocalDate d = LocalDate.of(2026, 9, 30);
        assertThat(ClickHouseSql.datePartition(d)).isEqualTo("'2026-09-30'");
        assertThat(ClickHouseSql.monthPartition(d)).isEqualTo("202609");
    }

    @Test
    void quoteStringEscapes() {
        assertThat(ClickHouseSql.quoteString("it's a \\ test")).isEqualTo("'it\\'s a \\\\ test'");
        assertThat(ClickHouseSql.quoteString(null)).isEqualTo("NULL");
    }

    @Test
    void insertStatement() {
        assertThat(ClickHouseSql.insert("rt.movie_popularity", Arrays.asList("movie_id", "popularity", "ts")))
                .isEqualTo("INSERT INTO rt.movie_popularity (movie_id, popularity, ts) VALUES (?, ?, ?)");
        assertThatThrownBy(() -> ClickHouseSql.insert("t", Collections.emptyList()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClickHouseSql.insert("t", Collections.singletonList("a b")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ddlWithAndWithoutCluster() {
        assertThat(ClickHouseSql.replacePartition("dwd.fact", "dwd.fact_stg", "'2026-09-30'", ""))
                .isEqualTo("ALTER TABLE dwd.fact REPLACE PARTITION '2026-09-30' FROM dwd.fact_stg");
        assertThat(ClickHouseSql.replacePartition("dwd.fact", "dwd.fact_stg", "202609", "tmdb"))
                .isEqualTo("ALTER TABLE dwd.fact ON CLUSTER tmdb REPLACE PARTITION 202609 FROM dwd.fact_stg");
        assertThat(ClickHouseSql.dropPartition("ods.t", "202601", null))
                .isEqualTo("ALTER TABLE ods.t DROP PARTITION 202601");
        assertThat(ClickHouseSql.createTableAs("dwd.a_stg", "dwd.a", "c1"))
                .isEqualTo("CREATE TABLE IF NOT EXISTS dwd.a_stg ON CLUSTER c1 AS dwd.a");
        assertThat(ClickHouseSql.dropTable("dwd.a_stg", "")).isEqualTo("DROP TABLE IF EXISTS dwd.a_stg SYNC");
        assertThat(ClickHouseSql.truncate("dwd.a", "")).isEqualTo("TRUNCATE TABLE IF EXISTS dwd.a");
        assertThatThrownBy(() -> ClickHouseSql.onCluster("bad cluster")).isInstanceOf(IllegalArgumentException.class);
    }
}
