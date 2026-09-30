package com.tmdbwh.common.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CheckpointStoreTest {

    /** 增量水位线状态（与采集模块的结构保持一致，这里用最小结构验证存储语义）。 */
    public static class WatermarkState {
        private String date;

        public WatermarkState() {}

        public WatermarkState(String date) {
            this.date = date;
        }

        public String getDate() {
            return date;
        }

        public void setDate(String date) {
            this.date = date;
        }
    }

    private final MemoryObjectStore store = MemoryObjectStore.create("tmdb-lake");

    @Test
    void firstRunHasNoState() {
        CheckpointStore<WatermarkState> checkpoints = new CheckpointStore<>(store, "incremental_changes");
        assertThat(checkpoints.load(WatermarkState.class)).isEmpty();
        assertThat(checkpoints.key()).isEqualTo("_state/incremental_changes.json");
    }

    @Test
    void saveThenLoadRoundTrip() {
        CheckpointStore<WatermarkState> checkpoints = new CheckpointStore<>(store, "full_load_movie");

        checkpoints.save(new WatermarkState("2026-09-30"));

        Optional<WatermarkState> loaded = checkpoints.load(WatermarkState.class);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getDate()).isEqualTo("2026-09-30");
    }

    @Test
    void overwriteIsAtomicAndReadable() {
        CheckpointStore<WatermarkState> checkpoints = new CheckpointStore<>(store, "full_load_movie");
        checkpoints.save(new WatermarkState("2026-09-01"));

        checkpoints.save(new WatermarkState("2026-09-15"));

        // 覆盖写：读到的必须是最新完整状态，不存在"半个文件"
        assertThat(checkpoints.load(WatermarkState.class).get().getDate()).isEqualTo("2026-09-15");
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void updateReadModifyWrite() {
        CheckpointStore<WatermarkState> checkpoints = new CheckpointStore<>(store, "incremental_changes");

        WatermarkState first = checkpoints.update(new WatermarkState("2026-09-01"), s -> s);
        WatermarkState second = checkpoints.update(new WatermarkState("2026-09-01"),
                s -> new WatermarkState(LocalDate.parse(s.getDate()).plusDays(1).toString()));

        assertThat(first.getDate()).isEqualTo("2026-09-01");
        assertThat(second.getDate()).isEqualTo("2026-09-02");
        assertThat(checkpoints.load(WatermarkState.class).get().getDate()).isEqualTo("2026-09-02");
    }

    @Test
    void deleteClearsState() {
        CheckpointStore<WatermarkState> checkpoints = new CheckpointStore<>(store, "full_load_movie");
        checkpoints.save(new WatermarkState("2026-09-30"));

        checkpoints.delete();

        assertThat(checkpoints.load(WatermarkState.class)).isEmpty();
    }

    @Test
    void stateSurvivesRestart() {
        // 模拟"上一次运行留下状态文件，新作业实例读回"
        MemoryObjectStore existing = MemoryObjectStore.of("tmdb-lake",
                java.util.Map.of("_state/full_load_movie.json",
                        "{\"date\":\"2026-09-20\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        CheckpointStore<WatermarkState> checkpoints = new CheckpointStore<>(existing, "full_load_movie");

        assertThat(checkpoints.load(WatermarkState.class).get().getDate()).isEqualTo("2026-09-20");
    }

    @Test
    void rejectsUnsafeJobName() {
        MemoryObjectStore s = MemoryObjectStore.create("tmdb-lake");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new CheckpointStore<WatermarkState>(s, "../evil"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
