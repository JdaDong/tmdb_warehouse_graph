package com.tmdbwh.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class TimeUtilsTest {

    private static final LocalDate D = LocalDate.of(2026, 9, 30);

    @Test
    void parseAndFormatDt() {
        assertThat(TimeUtils.parseDt(" 2026-09-30 ")).isEqualTo(D);
        assertThat(TimeUtils.formatDt(D)).isEqualTo("2026-09-30");
        assertThatThrownBy(() -> TimeUtils.parseDt("2026/09/30"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("yyyy-MM-dd");
    }

    @Test
    void partitionHelpers() {
        assertThat(TimeUtils.yyyymm(D)).isEqualTo(202609);
        assertThat(TimeUtils.exportFileDate(D)).isEqualTo("09_30_2026");
    }

    @Test
    void todayAndYesterdayRespectZone() {
        // UTC 2026-09-30 20:00 = 上海 2026-10-01 04:00
        Clock clock = Clock.fixed(Instant.parse("2026-09-30T20:00:00Z"), ZoneOffset.UTC);
        assertThat(TimeUtils.today(clock, TimeUtils.UTC)).isEqualTo(D);
        assertThat(TimeUtils.today(clock, ZoneId.of("Asia/Shanghai"))).isEqualTo(D.plusDays(1));
        assertThat(TimeUtils.yesterday(clock, TimeUtils.UTC)).isEqualTo(D.minusDays(1));
    }

    @Test
    void epochConversions() {
        long millis = TimeUtils.startOfDayMillis(D, TimeUtils.UTC);
        assertThat(Instant.ofEpochMilli(millis)).isEqualTo(Instant.parse("2026-09-30T00:00:00Z"));
        assertThat(TimeUtils.toLocalDate(millis + 1, TimeUtils.UTC)).isEqualTo(D);
        assertThat(TimeUtils.toLocalDate(millis - 1, TimeUtils.UTC)).isEqualTo(D.minusDays(1));
    }

    @Test
    void dateRangeInclusive() {
        assertThat(TimeUtils.dateRange(D, D.plusDays(2))).containsExactly(D, D.plusDays(1), D.plusDays(2));
        assertThat(TimeUtils.dateRange(D, D)).containsExactly(D);
        assertThat(TimeUtils.dateRange(D.plusDays(1), D)).isEmpty();
    }

    @Test
    void splitWindowsForChangesApiLimit() {
        LocalDate start = LocalDate.of(2026, 9, 1);
        List<DateWindow> windows = TimeUtils.splitWindows(start, D, 14);

        assertThat(windows).containsExactly(
                new DateWindow(start, LocalDate.of(2026, 9, 14)),
                new DateWindow(LocalDate.of(2026, 9, 15), LocalDate.of(2026, 9, 28)),
                new DateWindow(LocalDate.of(2026, 9, 29), D));
        assertThat(windows).allSatisfy(w -> assertThat(w.days()).isLessThanOrEqualTo(14));
        assertThat(windows.stream().mapToLong(DateWindow::days).sum()).isEqualTo(30);
        assertThat(TimeUtils.splitWindows(D, D, 14)).containsExactly(new DateWindow(D, D));
        assertThat(TimeUtils.splitWindows(D.plusDays(1), D, 14)).isEmpty();
        assertThatThrownBy(() -> TimeUtils.splitWindows(D, D, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lenientTmdbDateParsing() {
        assertThat(TimeUtils.parseTmdbDate("2010-07-15")).contains(LocalDate.of(2010, 7, 15));
        assertThat(TimeUtils.parseTmdbDate("2010-07-16T00:00:00.000Z")).contains(LocalDate.of(2010, 7, 16));
        assertThat(TimeUtils.parseTmdbDate("")).isEmpty();
        assertThat(TimeUtils.parseTmdbDate(null)).isEmpty();
        assertThat(TimeUtils.parseTmdbDate("2010-13-45")).isEmpty();
        assertThat(TimeUtils.parseTmdbDate("unknown")).isEmpty();
    }

    @Test
    void lenientTmdbInstantParsing() {
        assertThat(TimeUtils.parseTmdbInstant("2010-07-16T00:00:00.000Z"))
                .contains(Instant.parse("2010-07-16T00:00:00Z"));
        assertThat(TimeUtils.parseTmdbInstant("2010-07-16")).contains(Instant.parse("2010-07-16T00:00:00Z"));
        assertThat(TimeUtils.parseTmdbInstant(" ")).isEmpty();
        assertThat(TimeUtils.parseTmdbInstant(null)).isEmpty();
        assertThat(TimeUtils.parseTmdbInstant("garbage")).isEmpty();
    }

    @Test
    void dateWindowSemantics() {
        DateWindow w = new DateWindow(D, D.plusDays(2));
        assertThat(w.contains(D.plusDays(1))).isTrue();
        assertThat(w.contains(D.plusDays(3))).isFalse();
        assertThat(w.contains(D.minusDays(1))).isFalse();
        assertThat(w.days()).isEqualTo(3);
        assertThat(w.toString()).isEqualTo("[2026-09-30, 2026-10-02]");
        assertThat(w).isEqualTo(new DateWindow(D, D.plusDays(2))).hasSameHashCodeAs(new DateWindow(D, D.plusDays(2)));
        assertThat(w).isNotEqualTo(new DateWindow(D, D));
        assertThatThrownBy(() -> new DateWindow(D, D.minusDays(1))).isInstanceOf(IllegalArgumentException.class);
    }
}
