package com.tmdbwh.common.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LakePathsTest {

    private static final LocalDate D = LocalDate.of(2026, 9, 30);

    @Test
    void layoutConventions() {
        assertThat(LakePaths.rawPartition("movie", D)).isEqualTo("raw/movie/dt=2026-09-30/");
        assertThat(LakePaths.rawFile("change_movie", D, "run-20260930t0100", 7))
                .isEqualTo("raw/change_movie/dt=2026-09-30/part-run-20260930t0100-00007.ndjson.gz");
        assertThat(LakePaths.quarantinePartition("person", D)).isEqualTo("quarantine/person/dt=2026-09-30/");
        assertThat(LakePaths.state("full_load_movie")).isEqualTo("_state/full_load_movie.json");
        assertThat(LakePaths.checkpoints("entity_change_job")).isEqualTo("checkpoints/entity_change_job/");
        assertThat(LakePaths.s3a("tmdb-lake", "/raw/movie/")).isEqualTo("s3a://tmdb-lake/raw/movie/");
    }

    @Test
    void parseDtFromKey() {
        assertThat(LakePaths.parseDt("raw/movie/dt=2026-09-30/part-a-00001.ndjson.gz")).isEqualTo(D);
        assertThat(LakePaths.parseDt("raw/movie/part.gz")).isNull();
        assertThat(LakePaths.parseDt("raw/movie/dt=2026-99-99/x")).isNull();
        assertThat(LakePaths.parseDt("raw/movie/dt=20")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"../etc", "Movie", "a/b", "", " movie", "movie;rm", "_state"})
    void rejectsUnsafeSegments(String segment) {
        assertThatThrownBy(() -> LakePaths.rawPartition(segment, D)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullSegment() {
        assertThatThrownBy(() -> LakePaths.state(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
