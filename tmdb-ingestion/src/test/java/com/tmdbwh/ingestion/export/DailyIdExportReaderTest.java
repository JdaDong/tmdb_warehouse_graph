package com.tmdbwh.ingestion.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tmdbwh.common.exception.TmdbWhException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

class DailyIdExportReaderTest {

    private static final String NDJSON = String.join("\n",
            "{\"id\":27205,\"title\":\"Inception\",\"popularity\":83.952,\"adult\":false,\"video\":false}",
            "{\"id\":155,\"title\":\"The Dark Knight\",\"popularity\":105.692,\"adult\":false}",
            "{\"id\":1399,\"name\":\"Game of Thrones\",\"popularity\":346.098,\"adult\":false}");

    private static byte[] gzip(String content) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return bos.toByteArray();
    }

    @Test
    void readsPlainNdjson() {
        List<ExportIdRecord> records =
                DailyIdExportReader.read(new ByteArrayInputStream(NDJSON.getBytes(StandardCharsets.UTF_8)), 0);

        assertThat(records).hasSize(3);
        assertThat(records).extracting(ExportIdRecord::getId).containsExactly(27205L, 155L, 1399L);
        assertThat(records.get(0).getTitle()).isEqualTo("Inception");
        assertThat(records.get(0).getPopularity()).isEqualTo(83.952);
        assertThat(records.get(2).getName()).isEqualTo("Game of Thrones");
        assertThat(records.get(2).displayName()).isEqualTo("Game of Thrones");
        assertThat(records.get(0).displayName()).isEqualTo("Inception");
    }

    @Test
    void detectsGzipByMagicNumber() throws IOException {
        // 真实 TMDB 的导出文件是 gzip 压缩的；服务端返回时不一定带 Content-Encoding，因此按魔数识别
        List<ExportIdRecord> records = DailyIdExportReader.read(new ByteArrayInputStream(gzip(NDJSON)), 0);
        assertThat(records).extracting(ExportIdRecord::getId).containsExactly(27205L, 155L, 1399L);
    }

    @Test
    void respectsLimit() {
        List<ExportIdRecord> records =
                DailyIdExportReader.read(new ByteArrayInputStream(NDJSON.getBytes(StandardCharsets.UTF_8)), 2);
        assertThat(records).extracting(ExportIdRecord::getId).containsExactly(27205L, 155L);
    }

    @Test
    void skipsMalformedLinesButKeepsGoodOnes() {
        String mixed = NDJSON + "\n{broken\n{\"no_id\":1}\n\n{\"id\":603,\"title\":\"The Matrix\"}";
        List<ExportIdRecord> records =
                DailyIdExportReader.read(new ByteArrayInputStream(mixed.getBytes(StandardCharsets.UTF_8)), 0);
        assertThat(records).extracting(ExportIdRecord::getId).containsExactly(27205L, 155L, 1399L, 603L);
    }

    @Test
    void emptyOrAllMalformedInputFails() {
        assertThatThrownBy(() -> DailyIdExportReader.read(new ByteArrayInputStream(new byte[0]), 0))
                .isInstanceOf(TmdbWhException.class)
                .hasMessageContaining("没有可用记录");
        assertThatThrownBy(() -> DailyIdExportReader.read(
                new ByteArrayInputStream("{broken".getBytes(StandardCharsets.UTF_8)), 0))
                .isInstanceOf(TmdbWhException.class);
        assertThatThrownBy(() -> DailyIdExportReader.read(null, 0)).isInstanceOf(TmdbWhException.class);
    }

    @Test
    void parseLineReturnsEmptyForInvalidInput() {
        assertThat(DailyIdExportReader.parseLine("{\"id\":1}")).isPresent();
        assertThat(DailyIdExportReader.parseLine("{}")).isEmpty();
        assertThat(DailyIdExportReader.parseLine("nonsense")).isEmpty();
    }
}
