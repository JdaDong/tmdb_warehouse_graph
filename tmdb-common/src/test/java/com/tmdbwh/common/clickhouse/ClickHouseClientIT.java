package com.tmdbwh.common.clickhouse;

import static org.assertj.core.api.Assertions.assertThat;

import com.tmdbwh.common.config.ClickHouseConfig;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 基于真实 ClickHouse 24.3 的集成测试：验证批量写入与分区原子替换的幂等性。 */
@Testcontainers(disabledWithoutDocker = true)
class ClickHouseClientIT {

    @Container
    static final ClickHouseContainer CH = new ClickHouseContainer("clickhouse/clickhouse-server:24.3");

    private static ClickHouseClient client;

    @BeforeAll
    static void init() {
        client = ClickHouseClient.create(new ClickHouseConfig(CH.getJdbcUrl(), CH.getUsername(), CH.getPassword(),
                "", 2, 1000));
        client.executeScript("CREATE DATABASE IF NOT EXISTS it_dwd;\n"
                + "CREATE TABLE IF NOT EXISTS it_dwd.fact_snapshot (\n"
                + "  dt Date COMMENT '业务日期',\n"
                + "  movie_id UInt64 COMMENT '电影ID',\n"
                + "  popularity Float64 COMMENT '热度'\n"
                + ") ENGINE = MergeTree PARTITION BY dt ORDER BY movie_id;");
    }

    @AfterAll
    static void close() {
        if (client != null) {
            client.close();
        }
    }

    private static List<Object[]> snapshot(LocalDate dt, int n, double popularity) {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            rows.add(new Object[] {dt, (long) i, popularity});
        }
        return rows;
    }

    @Test
    void replacePartitionIsIdempotentOnRerun() {
        LocalDate dt = LocalDate.of(2026, 9, 30);
        List<String> cols = Arrays.asList("dt", "movie_id", "popularity");
        String partition = ClickHouseSql.datePartition(dt);

        // 首次发布 2500 行
        long first = client.replacePartitionFromStaging("it_dwd.fact_snapshot", partition,
                stg -> client.batchInsert(stg, cols, snapshot(dt, 2500, 1.0)));
        // 重跑：同一分区发布 2000 行，应整体替换而非追加
        long second = client.replacePartitionFromStaging("it_dwd.fact_snapshot", partition,
                stg -> client.batchInsert(stg, cols, snapshot(dt, 2000, 2.0)));

        assertThat(first).isEqualTo(2500);
        assertThat(second).isEqualTo(2000);
        assertThat(client.queryForLong("SELECT count() FROM it_dwd.fact_snapshot WHERE dt = ?", dt)).isEqualTo(2000);
        assertThat(client.queryForString("SELECT toString(max(popularity)) FROM it_dwd.fact_snapshot")).contains("2");
        assertThat(client.tableExists("it_dwd.fact_snapshot_stg")).isFalse();
        assertThat(client.tableExists("it_dwd.fact_snapshot")).isTrue();
    }
}
