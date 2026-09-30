package com.tmdbwh.common.util;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * 闭区间日期窗口 [start, end]。
 *
 * <p>典型用途：TMDB changes 接口单次查询最多跨 14 天，长区间回溯需切分成多个窗口依次处理。
 */
public final class DateWindow implements Serializable {

    private static final long serialVersionUID = 1L;

    private final LocalDate start;
    private final LocalDate end;

    /**
     * 构造窗口。
     *
     * @throws IllegalArgumentException 当 start 晚于 end
     */
    public DateWindow(LocalDate start, LocalDate end) {
        this.start = Objects.requireNonNull(start, "start");
        this.end = Objects.requireNonNull(end, "end");
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("窗口起点 " + start + " 晚于终点 " + end);
        }
    }

    public LocalDate getStart() {
        return start;
    }

    public LocalDate getEnd() {
        return end;
    }

    /** 窗口包含的天数（闭区间，最小为 1）。 */
    public long days() {
        return ChronoUnit.DAYS.between(start, end) + 1;
    }

    /** 判断日期是否落在窗口内。 */
    public boolean contains(LocalDate date) {
        return !date.isBefore(start) && !date.isAfter(end);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DateWindow)) {
            return false;
        }
        DateWindow that = (DateWindow) o;
        return start.equals(that.start) && end.equals(that.end);
    }

    @Override
    public int hashCode() {
        return Objects.hash(start, end);
    }

    @Override
    public String toString() {
        return "[" + start + ", " + end + "]";
    }
}
