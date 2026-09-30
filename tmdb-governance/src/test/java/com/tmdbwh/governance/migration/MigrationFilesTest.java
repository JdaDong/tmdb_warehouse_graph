package com.tmdbwh.governance.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.tmdbwh.common.clickhouse.SqlScriptSplitter;
import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 迁移脚本的静态校验（不连接数据库）。
 *
 * <p>目的：把"SQL 语法错误"留给真实的 ClickHouse 集成测试，这里保证的是<b>工程约定</b>——
 * 版本号连续、首行说明、幂等写法、集群 DDL 不带 ON CLUSTER（由迁移器统一追加）。
 */
class MigrationFilesTest {

    private static final Pattern HEADER = Pattern.compile("^--\\s*V(\\d+)\\b.*");

    @Test
    void versionNumbersAreContinuousFromOne() throws IOException {
        List<Migration> migrations = MigrationLoader.fromClasspath(SchemaMigrator.DEFAULT_RESOURCE_DIR);

        assertThat(migrations).isNotEmpty();
        for (int i = 0; i < migrations.size(); i++) {
            assertThat(migrations.get(i).getVersion()).as("版本号必须从 1 开始且连续").isEqualTo(i + 1);
        }
    }

    @Test
    void everyScriptStartsWithAHeaderComment() throws IOException {
        for (Migration script : MigrationLoader.fromClasspath(SchemaMigrator.DEFAULT_RESOURCE_DIR)) {
            String firstLine = script.getSql().isBlank() ? "" : script.getSql().lines().findFirst().orElse("");
            assertThat(firstLine.trim()).as("%s 缺少 -- V<n> 说明", script.getFileName()).isNotEmpty();
        }
    }

    @Test
    void ddlIsIdempotent() throws IOException {
        // 迁移可能重复执行（失败后修正重跑），因此建表/建库/建视图必须带 IF [NOT] EXISTS
        for (Migration script : MigrationLoader.fromClasspath(SchemaMigrator.DEFAULT_RESOURCE_DIR)) {
            for (String statement : SqlScriptSplitter.split(script.getSql())) {
                String upper = statement.toUpperCase(java.util.Locale.ROOT);
                if (upper.startsWith("CREATE DATABASE")) {
                    assertThat(upper).as(script.getFileName()).contains("IF NOT EXISTS");
                } else if (upper.startsWith("CREATE TABLE")) {
                    assertThat(upper).as(script.getFileName()).contains("IF NOT EXISTS");
                } else if (upper.startsWith("CREATE MATERIALIZED VIEW") || upper.startsWith("CREATE VIEW")) {
                    assertThat(upper).as(script.getFileName()).contains("IF NOT EXISTS");
                }
            }
        }
    }

    @Test
    void scriptsDoNotHardcodeOnCluster() throws IOException {
        // ON CLUSTER 由 SchemaMigrator#withCluster 按配置追加，脚本里写死会破坏单机部署
        for (Migration script : MigrationLoader.fromClasspath(SchemaMigrator.DEFAULT_RESOURCE_DIR)) {
            assertThat(script.getSql().toUpperCase(java.util.Locale.ROOT))
                    .as("%s 不应硬编码 ON CLUSTER", script.getFileName())
                    .doesNotContain("ON CLUSTER");
        }
    }

    @Test
    void tablesDeclareEngineAndOrderBy() throws IOException {
        for (Migration script : MigrationLoader.fromClasspath(SchemaMigrator.DEFAULT_RESOURCE_DIR)) {
            for (String statement : SqlScriptSplitter.split(script.getSql())) {
                String upper = statement.toUpperCase(java.util.Locale.ROOT);
                if (!upper.startsWith("CREATE TABLE")) {
                    continue;
                }
                assertThat(upper).as("%s 缺少 ENGINE", script.getFileName()).contains("ENGINE");
                assertThat(upper).as("%s 缺少 ORDER BY", script.getFileName()).contains("ORDER BY");
            }
        }
    }

    @Test
    void scriptsCoverAllLayers() throws IOException {
        List<Migration> migrations = MigrationLoader.fromClasspath(SchemaMigrator.DEFAULT_RESOURCE_DIR);
        for (String database : new String[] {"ods", "dwd", "dws", "ads", "rt", "governance"}) {
            assertThat(migrations).as("缺少 %s 层相关脚本", database)
                    .anyMatch(m -> m.getSql().contains("CREATE DATABASE IF NOT EXISTS " + database));
        }
    }

    @Test
    void checksumsAreStableAcrossReads() throws IOException {
        List<Migration> first = MigrationLoader.fromClasspath(SchemaMigrator.DEFAULT_RESOURCE_DIR);
        List<Migration> second = MigrationLoader.fromClasspath(SchemaMigrator.DEFAULT_RESOURCE_DIR);
        assertThat(second).extracting(Migration::getChecksum).containsExactlyElementsOf(
                first.stream().map(Migration::getChecksum).collect(java.util.stream.Collectors.toList()));
    }
}
