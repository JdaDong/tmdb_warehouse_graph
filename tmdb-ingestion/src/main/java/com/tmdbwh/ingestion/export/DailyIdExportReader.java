package com.tmdbwh.ingestion.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.tmdbwh.common.exception.TmdbWhException;
import com.tmdbwh.common.json.JsonUtils;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 解析 TMDB 每日 ID 导出文件（{@code movie_ids_MM_dd_yyyy.json.gz}）。
 *
 * <p>导出文件为 NDJSON（每行一个 JSON 对象），真实 TMDB 以 gzip 压缩返回。这里按 <b>gzip 魔数 0x1F8B</b>
 * 自动判断是否需要解压，因此同一套代码既能处理线上压缩文件，也能处理未压缩的本地样例（Mock 服务返回未压缩内容， 从而避免在代码仓库中存放二进制文件）。
 *
 * <p>流式读取：单行损坏只跳过该行，不中断整个采集批次。
 */
public final class DailyIdExportReader {

    private static final Logger LOG = LoggerFactory.getLogger(DailyIdExportReader.class);

    private static final int GZIP_MAGIC_0 = 0x1F;
    private static final int GZIP_MAGIC_1 = (byte) 0x8B;

    private DailyIdExportReader() {}

    /**
     * 解析导出文件。
     *
     * @param in 原始字节流（可能经过 gzip 压缩）；方法不负责关闭该流
     * @param limit 最多读取的记录数（&lt;=0 表示不限制）
     * @return 解析成功的记录列表
     * @throws TmdbWhException 数据为空（导出文件尚未生成或已损坏）
     */
    public static List<ExportIdRecord> read(InputStream in, int limit) {
        if (in == null) {
            throw new TmdbWhException("导出文件输入流为空");
        }
        List<ExportIdRecord> records = new ArrayList<>();
        long malformed = 0;
        try (BufferedReader reader = openReader(in)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (limit > 0 && records.size() >= limit) {
                    break;
                }
                if (line.isBlank()) {
                    continue;
                }
                Optional<ExportIdRecord> parsed = parseLine(line);
                if (parsed.isPresent()) {
                    records.add(parsed.get());
                } else {
                    malformed++;
                }
            }
        } catch (IOException e) {
            throw new TmdbWhException("读取导出文件失败: " + e.getMessage(), e);
        }
        if (records.isEmpty()) {
            throw new TmdbWhException("导出文件中没有可用记录（共跳过 " + malformed + " 行）");
        }
        if (malformed > 0) {
            // 单行损坏不影响整体，只汇总数量（避免在循环内逐行打印日志）
            LOG.warn("导出文件中有 {} 行无法解析，已跳过", malformed);
        }
        return records;
    }

    /** 解析单行；JSON 损坏或缺少 id 时返回 empty（不抛异常）。 */
    public static Optional<ExportIdRecord> parseLine(String line) {
        JsonNode node;
        try {
            node = JsonUtils.readTree(line);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (node == null || !node.isObject() || node.get("id") == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(JsonUtils.treeToValue(node, ExportIdRecord.class));
    }

    private static BufferedReader openReader(InputStream in) throws IOException {
        // ByteArrayInputStream 等支持 mark；网络流需要先包一层 Pushback 再判断，
        // 统一成 PushbackInputStream 可以避免两条分支各自的回退顺序问题
        // 若底层流不支持批量读满 2 字节（如直接包装 socket 的流），先套一层缓冲，保证魔数判断可靠
        InputStream buffered = in instanceof java.io.BufferedInputStream ? in : new java.io.BufferedInputStream(in, 8192);
        java.io.PushbackInputStream pb = new java.io.PushbackInputStream(buffered, 2);
        int b0 = pb.read();
        int b1 = pb.read();
        pb.unread(new byte[] {(byte) b0, (byte) b1}, 0, b1 < 0 ? 1 : 2);
        InputStream source = pb;
        // 注意把读到的 int 转回 byte 再比较：0x8B 作为 byte 是 -117，直接与 139 比较会永远不相等
        if ((byte) b0 == GZIP_MAGIC_0 && (byte) b1 == GZIP_MAGIC_1) {
            source = new GZIPInputStream(pb);
        }
        return new BufferedReader(new InputStreamReader(source, StandardCharsets.UTF_8));
    }
}
