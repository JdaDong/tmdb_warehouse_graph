package com.tmdbwh.governance.migration;

import java.util.List;
import java.util.Objects;

/** 迁移执行计划：待执行脚本 + 最新版本号。 */
public final class MigrationPlan {

    private final List<Migration> pending;
    private final int currentVersion;
    private final int targetVersion;

    MigrationPlan(List<Migration> pending, int currentVersion, int targetVersion) {
        this.pending = List.copyOf(Objects.requireNonNull(pending, "pending"));
        this.currentVersion = currentVersion;
        this.targetVersion = targetVersion;
    }

    /** 待执行的脚本（按版本升序）。 */
    public List<Migration> getPending() {
        return pending;
    }

    /** 当前库版本（0 表示空库）。 */
    public int getCurrentVersion() {
        return currentVersion;
    }

    /** 脚本中的最高版本。 */
    public int getTargetVersion() {
        return targetVersion;
    }

    public boolean isUpToDate() {
        return pending.isEmpty();
    }

    @Override
    public String toString() {
        return "MigrationPlan{current=" + currentVersion + ", target=" + targetVersion
                + ", pending=" + pending.size() + "}";
    }
}
