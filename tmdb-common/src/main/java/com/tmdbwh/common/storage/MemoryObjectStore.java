package com.tmdbwh.common.storage;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 内存版对象存储，仅用于单元测试与本地工具。
 *
 * <p>严格遵循 {@link ObjectStore} 的语义：覆盖写、缺失返回 empty、拒绝空前缀删除。 让采集断点续传、水位线推进等逻辑可以用"真实可读回"的实现做验证，而不是 mock。
 */
public final class MemoryObjectStore implements ObjectStore {

    private final Map<String, byte[]> objects = new TreeMap<>();
    private final String bucket;
    private boolean closed;

    private MemoryObjectStore(String bucket) {
        this.bucket = Objects.requireNonNull(bucket, "bucket");
    }

    /** 创建一个空的内存存储。 */
    public static MemoryObjectStore create(String bucket) {
        return new MemoryObjectStore(bucket);
    }

    /** 基于已有数据创建（模拟"上一次运行留下的状态"）。 */
    public static MemoryObjectStore of(String bucket, Map<String, byte[]> initial) {
        MemoryObjectStore s = new MemoryObjectStore(bucket);
        s.objects.putAll(initial);
        return s;
    }

    @Override
    public String getBucket() {
        return bucket;
    }

    @Override
    public void putJson(String key, String json) {
        putBytes(key, json.getBytes(StandardCharsets.UTF_8), "application/json");
    }

    @Override
    public void putBytes(String key, byte[] data, String contentType) {
        checkOpen();
        objects.put(key, Objects.requireNonNull(data, "data").clone());
    }

    @Override
    public Optional<byte[]> getBytes(String key) {
        checkOpen();
        byte[] data = objects.get(key);
        return data == null ? Optional.empty() : Optional.of(data.clone());
    }

    @Override
    public Optional<String> getString(String key) {
        return getBytes(key).map(b -> new String(b, StandardCharsets.UTF_8));
    }

    @Override
    public InputStream openStream(String key) {
        checkOpen();
        byte[] data = objects.get(key);
        if (data == null) {
            throw new com.tmdbwh.common.exception.StorageException("对象不存在: " + uri(key));

        }
        return new ByteArrayInputStream(data);
    }

    @Override
    public boolean exists(String key) {
        checkOpen();
        return objects.containsKey(key);
    }

    @Override
    public List<String> listKeys(String prefix) {
        checkOpen();
        List<String> keys = new ArrayList<>();
        for (String k : objects.keySet()) {
            if (prefix == null || prefix.isEmpty() || k.startsWith(prefix)) {
                keys.add(k);
            }
        }
        return keys;
    }

    @Override
    public void delete(String key) {
        checkOpen();
        objects.remove(key);
    }

    @Override
    public int deletePrefix(String prefix) {
        checkOpen();
        if (prefix == null || prefix.isEmpty() || "/".equals(prefix)) {
            throw new IllegalArgumentException("拒绝删除空前缀（会清空整个桶）");
        }
        List<String> victims = listKeys(prefix);
        victims.forEach(objects::remove);
        return victims.size();
    }

    @Override
    public String uri(String key) {
        return LakePaths.s3a(bucket, key == null ? "" : key);
    }

    @Override
    public void close() {
        closed = true;
    }

    /** 对象数量（测试断言用）。 */
    public int size() {
        return objects.size();
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("存储已关闭");
        }
    }
}
