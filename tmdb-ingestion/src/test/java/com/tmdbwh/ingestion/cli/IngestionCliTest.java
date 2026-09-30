package com.tmdbwh.ingestion.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tmdbwh.common.config.AppConfig;
import com.tmdbwh.common.model.EntityType;
import com.tmdbwh.ingestion.job.FullLoadJob;
import com.tmdbwh.ingestion.job.IncrementalChangesJob;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class IngestionCliTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-30T10:00:00Z"), ZoneOffset.UTC);

    @AfterEach
    void cleanUp() {
        IngestionCli.injectedConfig = null;
        IngestionCli.injectedClock = null;
    }

    private static void injectConfig() {
        IngestionCli.injectedConfig = AppConfig.from(com.typesafe.config.ConfigFactory.parseMap(
                java.util.Map.of(
                        "tmdbwh.tmdb.base-url", "http://127.0.0.1:1/3",
                        "tmdbwh.tmdb.bearer-token", "token",
                        "tmdbwh.tmdb.mock-mode", true,
                        "tmdbwh.s3.endpoint", "http://127.0.0.1:1",
                        "tmdbwh.s3.access-key", "ak",
                        "tmdbwh.s3.secret-key", "sk"))
                .withFallback(com.typesafe.config.ConfigFactory.defaultReference()));
        IngestionCli.injectedClock = CLOCK;
    }

    @Test
    void parseEntitiesAcceptsRepeatedAndCommaSeparatedValues() {
        assertThat(IngestionCli.parseEntities(new String[] {"movie"})).containsExactly(EntityType.MOVIE);
        assertThat(IngestionCli.parseEntities(new String[] {"movie", "tv"}))
                .containsExactly(EntityType.MOVIE, EntityType.TV);
        assertThat(IngestionCli.parseEntities(new String[] {"movie,tv,person"}))
                .containsExactly(EntityType.MOVIE, EntityType.TV, EntityType.PERSON);
        assertThat(IngestionCli.parseEntities(new String[] {" MOVIE , tv "}))
                .containsExactly(EntityType.MOVIE, EntityType.TV);
        // 缺省为电影：最常用的采集对象
        assertThat(IngestionCli.parseEntities(null)).containsExactly(EntityType.MOVIE);
        assertThat(IngestionCli.parseEntities(new String[0])).containsExactly(EntityType.MOVIE);
    }

    @Test
    void parseEntitiesRejectsUnknownType() {
        assertThatThrownBy(() -> IngestionCli.parseEntities(new String[] {"episode"}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseDateHandlesBlank() {
        assertThat(IngestionCli.parseDate("2026-09-30")).isEqualTo(java.time.LocalDate.of(2026, 9, 30));
        assertThat(IngestionCli.parseDate("  ")).isNull();
        assertThat(IngestionCli.parseDate(null)).isNull();
        assertThatThrownBy(() -> IngestionCli.parseDate("2026/09/30")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void topLevelCommandShowsUsage() {
        IngestionCli cli = new IngestionCli();
        int code = new CommandLine(cli).execute();
        assertThat(code).isZero();
    }

    @Test
    void helpOptionsWorkForEachSubcommand() {
        for (String sub : new String[] {"full", "incremental", "popularity"}) {
            IngestionCli cli = new IngestionCli();
            int code = new CommandLine(cli).execute(sub, "--help");
            assertThat(code).as(sub).isZero();
        }
    }

    @Test
    void fullCommandFailsGracefullyWhenTmdbUnreachable() {
        // 指向不可达地址：作业应记录失败并返回非 0 退出码，而不是抛出未捕获异常
        injectConfig();
        IngestionCli cli = new IngestionCli();
        cli.maxFailureRatio = 1.0; // 放宽阈值，确保返回值来自"执行失败"而非阈值判定

        int code = new CommandLine(cli).execute("full", "--entity", "movie", "--max-ids", "0",
                "--concurrency", "1");

        assertThat(code).isEqualTo(1);
    }

    @Test
    void jobOptionsValidateArguments() {
        assertThatThrownBy(() -> new FullLoadJob.Options().concurrency(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FullLoadJob.Options().checkpointEvery(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IncrementalChangesJob.Options().defaultStartDays(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new FullLoadJob.Options().getConcurrency()).isEqualTo(8);
        assertThat(new FullLoadJob.Options().isResume()).isTrue();
        assertThat(new FullLoadJob.Options().getEntityType()).isEqualTo(EntityType.MOVIE);
        assertThat(new IncrementalChangesJob.Options().getEntityTypes())
                .containsExactly(EntityType.MOVIE, EntityType.TV, EntityType.PERSON);
    }

    @Test
    void jobResultFailureThreshold() {
        com.tmdbwh.ingestion.job.JobResult ok = new com.tmdbwh.ingestion.job.JobResult("j");
        for (int i = 0; i < 10; i++) {
            ok.recordAttempted();
            ok.recordSuccess();
        }
        assertThat(ok.isFailure(0.05)).isFalse();
        assertThat(ok.failureRatio()).isZero();

        com.tmdbwh.ingestion.job.JobResult bad = new com.tmdbwh.ingestion.job.JobResult("j");
        for (int i = 0; i < 10; i++) {
            bad.recordAttempted();
            if (i < 9) {
                bad.recordSuccess();
            } else {
                bad.recordFailed();
            }
        }
        // 10% 失败率超过 5% 阈值 -> 判定作业失败
        assertThat(bad.isFailure(0.05)).isTrue();
        assertThat(bad.summary()).contains("total=10").contains("failed=1");

        com.tmdbwh.ingestion.job.JobResult empty = new com.tmdbwh.ingestion.job.JobResult("j");
        // 没有任何尝试时不判定为失败（例如"无需采集"）
        assertThat(empty.isFailure(0.05)).isFalse();
    }
}
