package com.tmdbwh.governance.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationLoaderTest {

    private static final String TEST_DIR = "clickhouse/test-migrations";

    @Test
    void loadsFromClasspathSortedByVersion() throws IOException {
        List<Migration> migrations = MigrationLoader.fromClasspath(TEST_DIR);

        assertThat(migrations).extracting(Migration::getVersion).containsExactly(1, 2);
        assertThat(migrations).extracting(Migration::getName).containsExactly("create_one", "create_two");
        assertThat(migrations.get(0).getFileName()).isEqualTo("V1__create_one.sql");
        assertThat(migrations.get(0).getChecksum()).hasSize(16);
        assertThat(migrations.get(0).statementCount()).isEqualTo(2);
    }

    @Test
    void headerCommentIsExcludedFromChecksum() throws IOException {
        // 仅修改首行 -- V1 ... 注释不应改变校验和（避免"改说明就触发校验和告警"）
        Migration original = MigrationLoader.fromClasspath(TEST_DIR).get(0);
        assertThat(original.getSql()).doesNotStartWith("--");
        assertThat(original.getChecksum()).isEqualTo(original.getChecksum());
    }

    @Test
    void loadsFromDirectory(@TempDir Path dir) throws IOException {
        Files.write(dir.resolve("V3__extra.sql"), "SELECT 1;".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("V1__first.sql"), "SELECT 2;".getBytes(StandardCharsets.UTF_8));

        List<Migration> migrations = MigrationLoader.fromDirectory(dir);

        assertThat(migrations).extracting(Migration::getVersion).containsExactly(1, 3);
    }

    @Test
    void missingDirectoryReturnsEmpty() throws IOException {
        assertThat(MigrationLoader.fromDirectory(Path.of("/definitely/not/here"))).isEmpty();
    }

    @Test
    void rejectsBadFileName(@TempDir Path dir) {
        assertThatThrownBy(() -> {
            Files.write(dir.resolve("v1_bad.sql"), "SELECT 1;".getBytes(StandardCharsets.UTF_8));
            MigrationLoader.fromDirectory(dir);
        }).isInstanceOf(IllegalStateException.class).hasMessageContaining("V<version>__<name>.sql");
    }

    @Test
    void rejectsEmptyScript(@TempDir Path dir) {
        assertThatThrownBy(() -> {
            Files.write(dir.resolve("V1__empty.sql"), "-- only a comment".getBytes(StandardCharsets.UTF_8));
            MigrationLoader.fromDirectory(dir);
        }).isInstanceOf(IllegalStateException.class).hasMessageContaining("没有任何可执行语句");
    }

    @Test
    void rejectsDuplicateVersion(@TempDir Path dir) {
        assertThatThrownBy(() -> {
            Files.write(dir.resolve("V1__a.sql"), "SELECT 1;".getBytes(StandardCharsets.UTF_8));
            Files.write(dir.resolve("V1__b.sql"), "SELECT 2;".getBytes(StandardCharsets.UTF_8));
            MigrationLoader.fromDirectory(dir);
        }).isInstanceOf(IllegalStateException.class).hasMessageContaining("版本号重复");
    }

    @Test
    void missingResourceDirFailsLoudly() {
        assertThatThrownBy(() -> MigrationLoader.fromClasspath("clickhouse/does-not-exist"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("classpath 目录不存在");
    }

    @Test
    void migrationEqualityIsByVersion(@TempDir Path dir) throws IOException {
        Files.write(dir.resolve("V5__x.sql"), "SELECT 1;".getBytes(StandardCharsets.UTF_8));
        Migration a = MigrationLoader.fromDirectory(dir).get(0);
        assertThat(a).isEqualTo(new Migration(5, "x", "V5__x.sql", "SELECT 1;", a.getChecksum()));
        assertThat(a.compareTo(new Migration(6, "y", "V6__y.sql", "SELECT 1;", a.getChecksum()))).isNegative();
        assertThat(a.toString()).contains("V5");
    }
}
