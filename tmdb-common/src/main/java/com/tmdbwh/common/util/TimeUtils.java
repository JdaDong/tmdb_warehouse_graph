package com.tmdbwh.common.util;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 日期 / 时间工具。
 *
 * <p>约定：
 *
 * <ul>
 *   <li>分区字段 {@code dt} 统一为 ISO 格式 {@code yyyy-MM-dd}；
 *   <li>事件时间一律使用 UTC epoch 毫秒存储，展示时再转换时区；
 *   <li>所有"当前时间"通过 {@link Clock} 注入，保证可测试。
 * </ul>
 */
public final class TimeUtils {

    /** 分区日期格式 yyyy-MM-dd。 */
    public static final DateTimeFormatter DT_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    /** 平台默认时区 UTC。 */
    public static final ZoneId UTC = ZoneOffset.UTC;

    private static final DateTimeFormatter YYYYMM = DateTimeFormatter.ofPattern("yyyyMM");
    private static final DateTimeFormatter EXPORT_DATE = DateTimeFormatter.ofPattern("MM_dd_yyyy");

    private TimeUtils() {}

    /**
     * 解析分区日期。
     *
     * @throws IllegalArgumentException 格式非法
     */
    public static LocalDate parseDt(String dt) {
        try {
            return LocalDate.parse(Objects.requireNonNull(dt, "dt").trim(), DT_FORMAT);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("非法的分区日期（期望 yyyy-MM-dd）: " + dt, e);
        }
    }

    /** 格式化为分区日期字符串。 */
    public static String formatDt(LocalDate date) {
        return DT_FORMAT.format(date);
    }

    /** 月分区键，例如 2026-09-30 → 202609（与 ClickHouse toYYYYMM 一致）。 */
    public static int yyyymm(LocalDate date) {
        return Integer.parseInt(YYYYMM.format(date));
    }

    /** TMDB 每日导出文件日期段，例如 2026-09-30 → 09_30_2026。 */
    public static String exportFileDate(LocalDate date) {
        return EXPORT_DATE.format(date);
    }

    /** 指定时区的"今天"。 */
    public static LocalDate today(Clock clock, ZoneId zone) {
        return LocalDate.now(clock.withZone(zone));
    }

    /** 指定时区的"昨天"，离线 T+1 任务的默认业务日期。 */
    public static LocalDate yesterday(Clock clock, ZoneId zone) {
        return today(clock, zone).minusDays(1);
    }

    /** 某日零点（指定时区）对应的 epoch 毫秒。 */
    public static long startOfDayMillis(LocalDate date, ZoneId zone) {
        return date.atStartOfDay(zone).toInstant().toEpochMilli();
    }

    /** epoch 毫秒 → 指定时区的日期。 */
    public static LocalDate toLocalDate(long epochMillis, ZoneId zone) {
        return Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate();
    }

    /**
     * 生成闭区间日期序列 [start, end]。
     *
     * @return start 晚于 end 时返回空列表
     */
    public static List<LocalDate> dateRange(LocalDate start, LocalDate end) {
        if (start.isAfter(end)) {
            return Collections.emptyList();
        }
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            days.add(d);
        }
        return days;
    }

    /**
     * 把 [start, end] 切成若干个不超过 {@code maxDays} 天的连续窗口。
     *
     * @param maxDays 单窗口最大天数，必须 ≥ 1（TMDB changes 接口为 14）
     */
    public static List<DateWindow> splitWindows(LocalDate start, LocalDate end, int maxDays) {
        if (maxDays < 1) {
            throw new IllegalArgumentException("maxDays 必须 >= 1");
        }
        if (start.isAfter(end)) {
            return Collections.emptyList();
        }
        List<DateWindow> windows = new ArrayList<>();
        LocalDate cursor = start;
        while (!cursor.isAfter(end)) {
            LocalDate windowEnd = cursor.plusDays(maxDays - 1L);
            if (windowEnd.isAfter(end)) {
                windowEnd = end;
            }
            windows.add(new DateWindow(cursor, windowEnd));
            cursor = windowEnd.plusDays(1);
        }
        return windows;
    }

    /**
     * 宽松解析 TMDB 日期字段（如 release_date / birthday）。
     *
     * <p>TMDB 中这些字段可能为 null、空串或非法值，统一返回 {@link Optional#empty()} 而不抛异常。
     */
    public static Optional<LocalDate> parseTmdbDate(String value) {
        if (value == null || value.trim().isEmpty()) {
            return Optional.empty();
        }
        String v = value.trim();
        try {
            if (v.length() > 10 && v.charAt(10) == 'T') {
                return Optional.of(OffsetDateTime.parse(v).toLocalDate());
            }
            return Optional.of(LocalDate.parse(v, DT_FORMAT));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /** 宽松解析 ISO-8601 时间戳（如 release_dates.release_date = 2010-07-15T00:00:00.000Z）。 */
    public static Optional<Instant> parseTmdbInstant(String value) {
        if (value == null || value.trim().isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(OffsetDateTime.parse(value.trim()).toInstant());
        } catch (DateTimeParseException e) {
            return parseTmdbDate(value).map(d -> d.atStartOfDay(UTC).toInstant());
        }
    }
}
