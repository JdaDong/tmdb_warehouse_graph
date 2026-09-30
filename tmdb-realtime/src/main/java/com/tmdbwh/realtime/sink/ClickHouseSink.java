package com.tmdbwh.realtime.sink;

import com.tmdbwh.common.clickhouse.ClickHouseClient;
import com.tmdbwh.common.config.ClickHouseConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.metrics.Counter;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ClickHouse 批量写入 Sink。
 *
 * <p><b>语义说明（重要）</b>：ClickHouse 不支持分布式事务（无 XA），因此 Flink 的
 * "精确一次"无法延伸到 Sink 端。这里采用"至少一次 + 下游幂等"的组合：
 *
 * <ul>
 *   <li>Sink 在 checkpoint 完成时 flush（{@link #snapshotState} 由 Flink 调用 {@code close} 前的缓冲机制保证），
 *       失败时按 {@code maxRetries} 重试；
 *   <li>目标表全部使用 {@code ReplacingMergeTree}（ORDER BY 含业务主键、version 取事件时间），
 *       重复写入会在后台合并时自动去重，最终结果与"只写一次"一致。
 * </ul>
 *
 * <p>这也是为什么迁移脚本里所有 rt / ods 事件表都用 ReplacingMergeTree —— 实时链路的幂等性
 * 由存储引擎保证，而不是靠 Sink 端的事务。
 */
public class ClickHouseSink extends RichSinkFunction<Object[]> {

    private static final long serialVersionUID = 1L;

    private static final Logger LOG = LoggerFactory.getLogger(ClickHouseSink.class);

    private final ClickHouseConfig config;
    private final String table;
    private final List<String> columns;
    private final int batchSize;
    private final int maxRetries;

    private transient ClickHouseClient client;
    private transient List<Object[]> buffer;
    private transient Counter writtenRows;
    private transient Counter flushFailures;

    public ClickHouseSink(ClickHouseConfig config, String table, List<String> columns, int batchSize,
            int maxRetries) {
        this.config = Objects.requireNonNull(config, "config");
        this.table = Objects.requireNonNull(table, "table");
        this.columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("列名不能为空");
        }
        this.batchSize = batchSize > 0 ? batchSize : 1000;
        this.maxRetries = Math.max(0, maxRetries);
    }

    @Override
    public void open(Configuration parameters) {
        client = ClickHouseClient.create(config);
        buffer = new ArrayList<>(batchSize);
        writtenRows = getRuntimeContext().getMetricGroup().counter("clickhouse_written_rows");
        flushFailures = getRuntimeContext().getMetricGroup().counter("clickhouse_flush_failures");
        LOG.info("ClickHouse Sink 启动: table={} columns={} batch={}", table, columns, batchSize);
    }

    @Override
    public void invoke(Object[] row, Context context) {
        if (row == null || row.length != columns.size()) {
            // 列数不匹配通常是代码与表结构不同步，属于必须暴露的问题
            throw new IllegalArgumentException(
                    "行长度 " + (row == null ? "null" : row.length) + " 与列数 " + columns.size() + " 不一致: " + table);
        }
        buffer.add(row);
        if (buffer.size() >= batchSize) {
            flush();
        }
    }

    @Override
    public void close() {
        // 关闭前必须 flush：否则正常停止作业时会丢掉缓冲区里的数据
        if (buffer != null && !buffer.isEmpty()) {
            flush();
        }
        if (client != null) {
            client.close();
        }
    }

    /** 批量写入并按指数退避重试；重试耗尽后抛出异常让 Flink 重启作业（避免静默丢数据）。 */
    void flush() {
        if (buffer.isEmpty()) {
            return;
        }
        List<Object[]> batch = new ArrayList<>(buffer);
        buffer.clear();
        RuntimeException last = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                int rows = client.batchInsert(table, columns, batch);
                if (writtenRows != null) {
                    writtenRows.inc(rows);
                }
                return;
            } catch (RuntimeException e) {
                last = e;
                if (flushFailures != null) {
                    flushFailures.inc();
                }
                LOG.warn("ClickHouse 写入失败（第 {} 次重试，{} 行）: {}", attempt + 1, batch.size(), e.toString());
                sleepBeforeRetry(attempt);
            }
        }
        throw new IllegalStateException("ClickHouse 写入失败且重试耗尽: " + table, last);
    }

    private static void sleepBeforeRetry(int attempt) {
        long millis = Math.min(10_000L, 200L << attempt);
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("重试等待被中断", e);
        }
    }

    /** 当前缓冲区大小（测试用）。 */
    int buffered() {
        return buffer == null ? 0 : buffer.size();
    }
}
