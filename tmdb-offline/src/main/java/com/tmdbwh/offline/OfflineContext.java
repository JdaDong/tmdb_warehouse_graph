package com.tmdbwh.offline;

import com.tmdbwh.common.config.AppConfig;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/**
 * 一次离线运行的上下文：业务日期、运行 ID、时区。
 *
 * <p>业务日期（dt）与运行 ID（runId）贯穿全部分层：
 *
 * <ul>
 *   <li>dt 决定分区，是"重跑某一天"的唯一入口；
 *   <li>runId 用于生成暂存表后缀，保证并发重跑不同批次时不会互相覆盖（ClickHouse 分区替换会用到）。
 * </ul>
 */
public final class OfflineContext {

    private final LocalDate businessDate;
    private final String runId;
    private final ZoneId businessZone;
    private final AppConfig config;

    public OfflineContext(AppConfig config, LocalDate businessDate, String runId) {
        this.config = Objects.requireNonNull(config, "config");
        this.businessDate = Objects.requireNonNull(businessDate, "businessDate");
        this.businessZone = config.getBusinessZone();
        this.runId = runId == null || runId.isEmpty()
                ? "r" + businessDate.toString().replace("-", "")
                : runId;
    }

    public static OfflineContext of(AppConfig config, LocalDate businessDate) {
        return new OfflineContext(config, businessDate, null);
    }

    /** 业务日期（yyyy-MM-dd）。 */
    public String dt() {
        return businessDate.toString();
    }

    public LocalDate businessDate() {
        return businessDate;
    }

    /** 本次运行 ID。 */
    public String runId() {
        return runId;
    }

    public ZoneId businessZone() {
        return businessZone;
    }

    public AppConfig config() {
        return config;
    }

    /** 暂存表名后缀：{@code _stg_<runId>}。 */
    public String stagingSuffix() {
        return "_stg_" + runId.replaceAll("[^A-Za-z0-9_]", "");
    }

    @Override
    public String toString() {
        return "OfflineContext{dt=" + businessDate + ", runId=" + runId + ", zone=" + businessZone + "}";
    }
}
