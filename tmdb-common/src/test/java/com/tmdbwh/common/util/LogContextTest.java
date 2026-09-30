package com.tmdbwh.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class LogContextTest {

    @AfterEach
    void clear() {
        MDC.clear();
    }

    @Test
    void setsFieldsAndRestoresOnClose() {
        MDC.put(LogContext.DT, "2026-09-29");
        try (LogContext ctx = LogContext.forJob("dwd_build").with(LogContext.DT, "2026-09-30")
                .with(LogContext.LAYER, "dwd")) {
            assertThat(MDC.get(LogContext.JOB)).isEqualTo("dwd_build");
            assertThat(MDC.get(LogContext.DT)).isEqualTo("2026-09-30");
            assertThat(MDC.get(LogContext.LAYER)).isEqualTo("dwd");
            assertThat(LogContext.currentTraceId()).hasSize(32);
        }
        assertThat(MDC.get(LogContext.JOB)).isNull();
        assertThat(MDC.get(LogContext.LAYER)).isNull();
        assertThat(MDC.get(LogContext.TRACE_ID)).isNull();
        assertThat(MDC.get(LogContext.DT)).isEqualTo("2026-09-29");
    }

    @Test
    void nestedContextsKeepOuterTraceId() {
        try (LogContext outer = LogContext.forJob("outer")) {
            String trace = LogContext.currentTraceId();
            try (LogContext inner = LogContext.forJob("inner").with(LogContext.ENTITY, "movie")) {
                assertThat(LogContext.currentTraceId()).isEqualTo(trace);
                assertThat(MDC.get(LogContext.JOB)).isEqualTo("inner");
            }
            assertThat(MDC.get(LogContext.JOB)).isEqualTo("outer");
            assertThat(MDC.get(LogContext.ENTITY)).isNull();
            assertThat(LogContext.currentTraceId()).isEqualTo(trace);
        }
    }

    @Test
    void nullValueRemovesFieldTemporarily() {
        MDC.put(LogContext.ENTITY, "tv");
        try (LogContext ctx = LogContext.empty().with(LogContext.ENTITY, null)) {
            assertThat(MDC.get(LogContext.ENTITY)).isNull();
        }
        assertThat(MDC.get(LogContext.ENTITY)).isEqualTo("tv");
    }
}
