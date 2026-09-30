package com.tmdbwh.common.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.tmdbwh.common.json.JsonUtils;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 采集进度 / 水位线存储。
 *
 * <p>状态以 JSON 存放在 {@code _state/{job}.json}（见 {@link LakePaths#state}）。 依赖对象存储单次 PUT 的原子可见性：作业崩溃不会留下"半个状态文件"，重启后要么读到上一次完整的进度，
 * 要么读到空（视为首次运行）。这就是断点续传与增量水位线不会错位的保证。
 *
 * <p>泛型 {@code T} 为状态类型（POJO 或 {@link JsonNode}）。状态缺失的判定：文件不存在、内容为空，或 JSON 为
 * {@code null}。
 *
 * <pre>{@code
 * CheckpointStore<FullLoadState> store = new CheckpointStore<>(objectStore, "full_load_movie");
 * FullLoadState s = store.load(FullLoadState.class).orElseGet(FullLoadState::new);
 * ... 每处理 500 条: s.setOffset(n); store.save(s);      // 周期性推进
 * }</pre>
 */
public class CheckpointStore<T> {

    private static final Logger LOG = LoggerFactory.getLogger(CheckpointStore.class);

    private final ObjectStore store;
    private final String jobName;

    /**
     * @param store 对象存储
     * @param jobName 作业名，决定状态文件路径（仅允许 [a-z0-9_-]）
     */
    public CheckpointStore(ObjectStore store, String jobName) {
        this.store = Objects.requireNonNull(store, "store");
        this.jobName = LakePaths.segment(jobName);
    }

    public String key() {
        return LakePaths.state(jobName);
    }

    /** 读取状态；不存在时返回 empty。 */
    public Optional<T> load(Class<T> type) {
        Objects.requireNonNull(type, "type");
        Optional<String> json = store.getString(key());
        if (json.isEmpty() || json.get().isBlank()) {
            LOG.info("未找到状态文件，视为首次运行: {}", key());
            return Optional.empty();
        }
        JsonNode node = JsonUtils.readTree(json.get());
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        return Optional.ofNullable(JsonUtils.treeToValue(node, type));
    }

    /** 以树模型读取（适用于动态结构的状态，如"每个实体类型一个水位线"）。 */
    public Optional<JsonNode> loadTree() {
        return store.getString(key())
                .filter(s -> !s.isBlank())
                .map(JsonUtils::readTree);
    }

    /** 原子覆盖写入状态。 */
    public void save(T state) {
        Objects.requireNonNull(state, "state");
        store.putJson(key(), JsonUtils.toJson(state));
    }

    /**
     * 读取 → 修改 → 写回。用于"处理成功后再推进水位线"的语义。
     *
     * @param fallback 首次运行时的初始状态
     * @param updater 更新函数，入参为当前状态
     * @return 写回后的状态
     */
    public T update(T fallback, Function<T, T> updater) {
        Objects.requireNonNull(fallback, "fallback");
        T current = load(fallback).orElse(fallback);
        T next = updater.apply(current);
        save(next);
        return next;
    }

    /** 删除状态（重新全量采集前清空断点）。 */
    public void delete() {
        store.delete(key());
    }

    @SuppressWarnings("unchecked")
    private Optional<T> load(T fallback) {
        return load((Class<T>) fallback.getClass());
    }
}
