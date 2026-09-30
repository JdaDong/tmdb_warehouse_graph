package com.tmdbwh.ingestion.sink;

import com.tmdbwh.common.storage.LakePaths;
import com.tmdbwh.common.storage.ObjectStore;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.GZIPOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 原始区 NDJSON.gz 滚动写入器。
 *
 * <p>流程：内存累积行 → 达到行数 / 字节阈值时落盘为本地临时 gz 文件 → 上传到 {@code
 * raw/{source}/dt=yyyy-MM-dd/part-{runId}-{seq}.ndjson.gz} → 删除临时文件。
 *
 * <p>设计取舍：
 *
 * <ul>
 *   <li>先落临时文件再上传，是因为对象存储不支持"追加写"，而 gzip 又需要一次性压缩完整数据块；
 *   <li>滚动大小默认 64MB：过小会导致对象存储中小文件过多（Spark 读取时 task 数膨胀），过大则单批次内存占用高；
 *   <li><b>必须显式调用 {@link #flush()}</b>（通常在 try-with-resources 结束前），否则不足一批的数据会丢失；
 *       {@link #close()} 只负责清理，不隐式提交，避免"意外提交半个批次"造成重复数据。
 * </ul>
 *
 * <p>线程安全：{@link #write} 加同步锁，可被采集线程池并发调用。
 */
public class LakeWriter implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(LakeWriter.class);
    private static final String TEMP_PREFIX = "tmdbwh-lake-";

    private final ObjectStore store;
    private final LakeWriterOptions options;
    private final List<String> writtenKeys = new ArrayList<>();

    private final StringBuilder buffer = new StringBuilder();
    private int bufferedLines;
    private long bufferedBytes;
    private int sequence;
    private boolean closed;

    public LakeWriter(ObjectStore store, LakeWriterOptions options) {
        this.store = Objects.requireNonNull(store, "store");
        this.options = Objects.requireNonNull(options, "options");
        // 提前校验来源名：非法路径应在作业启动阶段暴露，而不是等到写文件时才失败
        LakePaths.segment(options.getSource());
    }

    /** 追加一行（不含换行符，换行由本类补齐）。 */
    public synchronized void write(String line) {
        checkOpen();
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        buffer.append(line).append('\n');
        bufferedLines++;
        bufferedBytes += bytes.length + 1;
        if (shouldRollover()) {
            flushInternal();
        }
    }

    /** 追加一个对象的 JSON 表示（一行一条）。 */
    public void writeObject(Object value) {
        write(com.tmdbwh.common.json.JsonUtils.toJson(value));
    }

    /**
     * 把当前缓冲区中不足一批的数据提交为一个文件（不足一批则跳过）。
     *
     * @return 本次提交产生的对象键；无数据时返回 null
     */
    public synchronized String flush() {
        checkOpen();
        return flushInternal();
    }

    /** 已提交的对象键列表。 */
    public synchronized List<String> writtenKeys() {
        return new ArrayList<>(writtenKeys);
    }

    public synchronized long bufferedLines() {
        return bufferedLines;
    }

    private boolean shouldRollover() {
        return bufferedLines >= options.getRolloverLines() || bufferedBytes >= options.getRolloverBytes();
    }

    private String flushInternal() {
        if (bufferedLines == 0) {
            return null;
        }
        int index = sequence++;
        String key = LakePaths.rawFile(options.getSource(), options.getDt(), options.getRunId(), index);
        Path temp = null;
        try {
            temp = Files.createTempFile(TEMP_PREFIX, ".ndjson.gz");
            try (OutputStream os = Files.newOutputStream(temp);
                    GZIPOutputStream gzip = new GZIPOutputStream(os)) {
                gzip.write(buffer.toString().getBytes(StandardCharsets.UTF_8));
            }
            store.putBytes(key, Files.readAllBytes(temp), "application/gzip");
        } catch (IOException e) {
            throw new com.tmdbwh.common.exception.StorageException("写入原始区文件失败: " + key, e);
        } finally {
            deleteQuietly(temp);
            buffer.setLength(0);
            bufferedLines = 0;
            bufferedBytes = 0;
        }
        writtenKeys.add(key);
        LOG.debug("已提交原始区文件 {}（第 {} 个分片）", store.uri(key), index + 1);
        return key;
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOG.warn("删除临时文件失败: {}", path);
        }
    }

    /** 关闭：仅清理资源，不提交缓冲区（提交由 flush 显式触发）。 */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (bufferedLines > 0) {
            LOG.warn("关闭时缓冲区仍有 {} 行未提交（需先调用 flush()），已丢弃", bufferedLines);
        }
        buffer.setLength(0);
        bufferedLines = 0;
        bufferedBytes = 0;
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("LakeWriter 已关闭");
        }
    }
}
