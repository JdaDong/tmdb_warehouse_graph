package com.tmdbwh.offline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tmdbwh.common.config.AppConfig;
import com.typesafe.config.ConfigFactory;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** 上下文、列表达式工具等辅助类。 */
class SupportClassesTest {

    @Test
    void contextDerivesRunIdAndStagingSuffix() {
        OfflineContext context = new OfflineContext(AppConfig.from(ConfigFactory.defaultReference()),
                LocalDate.of(2026, 9, 30), null);

        assertThat(context.dt()).isEqualTo("2026-09-30");
        assertThat(context.runId()).isEqualTo("r20260930");
        // 暂存表后缀含 runId，保证并发重跑不同批次不会互相覆盖
        assertThat(context.stagingSuffix()).isEqualTo("_stg_r20260930");
        assertThat(context.toString()).contains("dt=2026-09-30");
    }

    @Test
    void stagingSuffixSanitizesUnsafeRunId() {
        OfflineContext context = new OfflineContext(AppConfig.from(ConfigFactory.defaultReference()),
                LocalDate.of(2026, 9, 30), "run-2026-09-30T10:00:00Z");
        // 冒号、点等字符不能出现在表名里
        assertThat(context.stagingSuffix()).matches("_stg_[A-Za-z0-9_]+");
    }

    @Test
    void lakeTableNames() {
        assertThat(LakeTables.qualified("dwd", "dim_movie")).isEqualTo("dwd.dim_movie");
        assertThatThrownBy(() -> LakeTables.qualified(null, "t")).isInstanceOf(NullPointerException.class);
    }

    @Test
    void schemasDeclareKeyFields() {
        assertThat(Schemas.movie().fieldNames()).contains("id", "title", "credits", "release_dates", "keywords");
        assertThat(Schemas.tv().fieldNames()).contains("id", "name", "number_of_seasons");
        assertThat(Schemas.person().fieldNames()).contains("id", "name", "known_for_department");
        // 剧集没有 release_dates 子资源
        assertThat(Schemas.tv().fieldNames()).doesNotContain("release_dates");
    }

    @Test
    void odsRawSchemaMatchesRawRecord() {
        // 外层结构与采集端 RawRecord 一致（snake_case）
        assertThat(OdsReader.RAW_SCHEMA.fieldNames())
                .containsExactly("schema_version", "entity_type", "entity_id", "dt", "ingest_time", "source",
                        "payload");
    }

    @Test
    void businessColumnListsAreNonEmpty() {
        assertThat(DwdTransform.MOVIE_BUSINESS_COLUMNS).contains("popularity", "vote_count", "budget");
        assertThat(DwdTransform.PERSON_BUSINESS_COLUMNS).contains("name", "known_for_department");
        // overview 不参与变化检测：文本噪声大且极少更新
        assertThat(DwdTransform.MOVIE_BUSINESS_COLUMNS).doesNotContain("overview");
    }
}
