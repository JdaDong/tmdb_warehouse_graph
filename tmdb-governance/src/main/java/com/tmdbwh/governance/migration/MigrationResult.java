package com.tmdbwh.governance.migration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 迁移执行结果：便于 CLI 输出摘要，也便于调度系统判断是否放行下游。 */
public final class MigrationResult {

    /** 单条迁移的执行情况。 */
    public static final class Applied {
        private final int version;
        private final String name;
        private final int statements;
        private final Duration duration;

        Applied(int version, String name, int statements, Duration duration) {
            this.version = version;
            this.name = name;
            this.statements = statements;
            this.duration = duration;
        }

        public int getVersion() {
            return version;
        }

        public String getName() {
            return name;
        }

        public int getStatements() {
            return statements;
        }

        public Duration getDuration() {
            return duration;
        }

        @Override
        public String toString() {
            return String.format("V%d %s（%d 条语句，%d ms）", version, name, statements, duration.toMillis());
        }
    }

    private final List<Applied> applied = new ArrayList<>();
    private final int beforeVersion;
    /** 随执行推进：始终等于"已执行到的最大版本"。 */
    private int afterVersion;

    MigrationResult(int beforeVersion, int afterVersion) {
        this.beforeVersion = beforeVersion;
        this.afterVersion = afterVersion;
    }

    void add(Applied item) {
        applied.add(Objects.requireNonNull(item, "item"));
        afterVersion = Math.max(afterVersion, item.getVersion());
    }

    /** 本次实际执行的迁移。 */
    public List<Applied> getApplied() {
        return List.copyOf(applied);
    }

    public int getBeforeVersion() {
        return beforeVersion;
    }

    /** 迁移执行后到达的版本（未执行任何脚本时等于 beforeVersion）。 */
    public int getAfterVersion() {
        return afterVersion;
    }

    public boolean isNoop() {
        return applied.isEmpty();
    }

    public String summary() {
        if (isNoop()) {
            return String.format("已是最新版本 V%d，无需迁移", afterVersion);
        }
        return String.format("迁移完成 V%d -> V%d，共执行 %d 个脚本", beforeVersion, afterVersion, applied.size());
    }
}
