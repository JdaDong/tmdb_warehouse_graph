package com.tmdbwh.ingestion.sink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tmdbwh.common.storage.LakePaths;
import com.tmdbwh.common.storage.ObjectStore;
import com.tmdbwh.ingestion.support.DirObjectStore;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LakeWriterTest {

    private static final LocalDate DT = LocalDate.of(2026, 9, 30);

    @TempDir
    Path dir;
    ObjectStore store;

    @BeforeEach
    void setUp() {
        store = new DirObjectStore(dir.resolve("objects"), "tmdb-lake");
    }

    private LakeWriter writer(long rolloverBytes, int rolloverLines) {
        return new LakeWriter(store, LakeWriterOptions.builder("movie", DT, "run-1")
                .rolloverBytes(rolloverBytes)
                .rolloverLines(rolloverLines)
                .build());
    }

    private static String readGzip(ObjectStore store, String key) throws IOException {
        try (InputStream in = new GZIPInputStream(store.openStream(key))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void flushWritesSingleGzipFileUnderLakePath() throws IOException {
        try (LakeWriter writer = writer(1024 * 1024, 1000)) {
            writer.write("{\"id\":1}");
            writer.write("{\"id\":2}");
            String key = writer.flush();
            assertThat(key).isEqualTo("raw/movie/dt=2026-09-30/part-run-1-00000.ndjson.gz");
        }

        List<String> keys = store.listKeys("raw/movie/dt=2026-09-30/");
        assertThat(keys).hasSize(1);
        assertThat(readGzip(store, keys.get(0))).isEqualTo("{\"id\":1}\n{\"id\":2}\n");
        assertThat(store.exists(keys.get(0))).isTrue();
    }

    @Test
    void rollsOverByLineCountAndIncrementsSequence() throws IOException {
        try (LakeWriter writer = writer(1024 * 1024, 3)) {
            for (int i = 1; i <= 7; i++) {
                writer.write("{\"id\":" + i + "}");
            }
            writer.flush();
            assertThat(writer.writtenKeys()).hasSize(3);
        }

        List<String> keys = store.listKeys("raw/movie/");
        assertThat(keys).containsExactly(
                "raw/movie/dt=2026-09-30/part-run-1-00000.ndjson.gz",
                "raw/movie/dt=2026-09-30/part-run-1-00001.ndjson.gz",
                "raw/movie/dt=2026-09-30/part-run-1-00002.ndjson.gz");
        assertThat(readGzip(store, keys.get(1))).isEqualTo("{\"id\":4}\n{\"id\":5}\n{\"id\":6}\n");
        assertThat(readGzip(store, keys.get(2))).isEqualTo("{\"id\":7}\n");
    }

    @Test
    void rollsOverByByteSize() throws IOException {
        try (LakeWriter writer = writer(64, 1000)) {
            for (int i = 0; i < 4; i++) {
                writer.write("0123456789012345678901234567890123456789");
            }
            writer.flush();
        }
        assertThat(store.listKeys("raw/movie/")).hasSizeGreaterThan(1);
    }

    @Test
    void flushWithoutDataDoesNotCreateFile() {
        try (LakeWriter writer = writer(1024, 100)) {
            assertThat(writer.flush()).isNull();
            assertThat(writer.writtenKeys()).isEmpty();
        }
        assertThat(store.listKeys("raw/")).isEmpty();
    }

    @Test
    void closeDoesNotCommitPendingData() {
        try (LakeWriter writer = writer(1024, 100)) {
            writer.write("{\"id\":1}");
        }
        // 设计约束：提交必须显式 flush，避免"意外提交半个批次"造成重复数据
        assertThat(store.listKeys("raw/")).isEmpty();
    }

    @Test
    void writeAfterCloseIsRejected() {
        LakeWriter writer = writer(1024, 100);
        writer.close();
        assertThatThrownBy(() -> writer.write("x")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> writer.flush()).isInstanceOf(IllegalStateException.class);
        writer.close(); // 幂等
    }

    @Test
    void tempFilesAreCleanedUp() {
        try (LakeWriter writer = writer(1024, 1)) {
            writer.write("a");
            writer.write("b");
        }
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            assertThat(files).noneMatch(p -> p.getFileName().toString().startsWith("tmdbwh-lake-"));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void writeObjectSerializesToJsonLine() throws IOException {
        try (LakeWriter writer = writer(1024, 100)) {
            writer.writeObject(new java.util.LinkedHashMap<String, Object>() {{
                put("id", 7);
                put("name", "热度");
            }});
            writer.flush();
        }
        List<String> keys = store.listKeys("raw/");
        assertThat(readGzip(store, keys.get(0))).contains("\"id\":7").contains("热度");
    }

    @Test
    void optionsValidateArguments() {
        assertThatThrownBy(() -> LakeWriterOptions.builder("movie", DT, "run").rolloverBytes(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LakeWriterOptions.builder("movie", DT, "run").rolloverLines(0))
                .isInstanceOf(IllegalArgumentException.class);
        // 来源名含空格/大写时，构造 writer 即应失败（提前暴露非法路径）
        assertThatThrownBy(() -> new LakeWriter(store, LakeWriterOptions.builder("bad source", DT, "run").build()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LakeWriter(store, LakeWriterOptions.builder("../evil", DT, "run").build()))
                .isInstanceOf(IllegalArgumentException.class);
        LakeWriterOptions opts = LakeWriterOptions.builder("movie", DT, "run").build();
        assertThat(opts.getRolloverBytes()).isEqualTo(64L * 1024 * 1024);
        assertThat(LakePaths.parseDt(LakePaths.rawFile("movie", DT, "run", 0))).isEqualTo(DT);
    }
}
