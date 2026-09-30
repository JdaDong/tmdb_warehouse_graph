package com.tmdbwh.ingestion.support;

import com.tmdbwh.common.exception.StorageException;
import com.tmdbwh.common.storage.LakePaths;
import com.tmdbwh.common.storage.ObjectStore;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 目录版对象存储（仅用于测试）：把对象写成临时目录下的普通文件。
 *
 * <p>这样单元测试可以直接读到 LakeWriter 产出的 .ndjson.gz 内容并解压校验， 而不需要 MinIO / Docker。
 */
public final class DirObjectStore implements ObjectStore {

    private final Path root;
    private final String bucket;

    public DirObjectStore(Path root, String bucket) {
        this.root = Objects.requireNonNull(root, "root");
        this.bucket = Objects.requireNonNull(bucket, "bucket");
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new StorageException("创建测试存储目录失败: " + root, e);
        }
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
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            // 先写临时文件再原子移动，模拟对象存储"写入即整体可见"的语义
            Path tmp = Files.createTempFile(target.getParent(), ".tmp-", ".part");
            Files.write(tmp, data);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new StorageException("写入失败: " + key, e);
        }
    }

    @Override
    public Optional<byte[]> getBytes(String key) {
        Path p = resolve(key);
        if (!Files.isRegularFile(p)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(p));
        } catch (IOException e) {
            throw new StorageException("读取失败: " + key, e);
        }
    }

    @Override
    public Optional<String> getString(String key) {
        return getBytes(key).map(b -> new String(b, StandardCharsets.UTF_8));
    }

    @Override
    public InputStream openStream(String key) {
        try {
            return Files.newInputStream(resolve(key));
        } catch (IOException e) {
            throw new StorageException("打开流失败: " + key, e);
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.isRegularFile(resolve(key));
    }

    @Override
    public List<String> listKeys(String prefix) {
        List<String> keys = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .map(root::relativize)
                    .map(Path::toString)
                    .map(s -> s.replace(java.io.File.separatorChar, '/'))
                    .filter(s -> prefix == null || prefix.isEmpty() || s.startsWith(prefix))
                    .sorted()
                    .forEach(keys::add);
        } catch (IOException e) {
            throw new StorageException("列举失败: " + prefix, e);
        }
        return keys;
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new StorageException("删除失败: " + key, e);
        }
    }

    @Override
    public int deletePrefix(String prefix) {
        if (prefix == null || prefix.isEmpty() || "/".equals(prefix)) {
            throw new IllegalArgumentException("拒绝删除空前缀（会清空整个桶）");
        }
        List<String> victims = listKeys(prefix);
        victims.forEach(this::delete);
        return victims.size();
    }

    @Override
    public String uri(String key) {
        return LakePaths.s3a(bucket, key == null ? "" : key);
    }

    @Override
    public void close() {
        // 目录由 JUnit 的 @TempDir 清理
    }

    private Path resolve(String key) {
        return root.resolve(key).normalize();
    }
}
