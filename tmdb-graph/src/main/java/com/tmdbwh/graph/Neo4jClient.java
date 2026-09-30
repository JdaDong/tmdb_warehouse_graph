package com.tmdbwh.graph;

import com.tmdbwh.common.config.Neo4jConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.TransactionContext;
import org.neo4j.driver.summary.ResultSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Neo4j 客户端封装。
 *
 * <p>封装的三件事：
 *
 * <ul>
 *   <li><b>批量写</b>：用 {@code UNWIND $rows} + 写事务，避免逐条提交（百万节点下逐条 commit 会跑几小时）；
 *   <li><b>事务语义</b>：写操作一律走 {@code executeWrite}，网络抖动时由 driver 自动重试；
 *   <li><b>默认数据库</b>：社区版只有 neo4j 库，但企业版常用多库，统一从配置读取而不是写死。
 * </ul>
 */
public class Neo4jClient implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Neo4jClient.class);

    private final Driver driver;
    private final SessionConfig sessionConfig;
    private final boolean owned;

    public Neo4jClient(Driver driver, String database, boolean owned) {
        this.driver = Objects.requireNonNull(driver, "driver");
        this.sessionConfig = SessionConfig.forDatabase(
                database == null || database.isEmpty() ? "neo4j" : database);
        this.owned = owned;
    }

    /** 由配置创建（拥有 driver，close 时一并关闭）。 */
    public static Neo4jClient create(Neo4jConfig config) {
        Objects.requireNonNull(config, "config");
        Driver driver = GraphDatabase.driver(config.getUri(),
                AuthTokens.basic(config.getUser(), config.getPassword()),
                org.neo4j.driver.Config.builder()
                        .withMaxConnectionPoolSize(config.getMaxConnectionPoolSize())
                        .build());
        return new Neo4jClient(driver, config.getDatabase(), true);
    }

    /** 初始化约束与索引（幂等，可重复执行）。 */
    public void initSchema() {
        for (String label : GraphModel.ALL_LABELS) {
            // 唯一性约束本身会创建索引，因此 Movie / Person 等不再单独建 key 索引
            run(Cypher.createConstraint(label));
        }
        // 名称索引：供模糊检索（标题、人名）
        run(Cypher.createNameIndex(GraphModel.LABEL_MOVIE));
        run(Cypher.createNameIndex(GraphModel.LABEL_TV));
        run(Cypher.createNameIndex(GraphModel.LABEL_PERSON));
        LOG.info("图模式初始化完成：{} 个标签", GraphModel.ALL_LABELS.size());
    }

    /** 执行无返回结果的语句（DDL / 删除）。 */
    public void run(String cypher) {
        try (Session session = driver.session(sessionConfig)) {
            session.executeWrite(tx -> {
                tx.run(cypher).consume();
                return null;
            });
        }
    }

    /**
     * 批量写入（UNWIND）。
     *
     * @param cypher 语句，需含 {@code $rows} 参数
     * @param rows 每行一个属性 Map
     * @return 本次写入影响的节点 / 关系变更数（counters 之和）
     */
    public long writeBatch(String cypher, List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0L;
        }
        try (Session session = driver.session(sessionConfig)) {
            return session.executeWrite((TransactionContext tx) -> {
                Result result = tx.run(cypher, org.neo4j.driver.Values.parameters("rows", rows));
                ResultSummary summary = result.consume();
                return summary.counters().nodesCreated()
                        + summary.counters().relationshipsCreated()
                        + summary.counters().propertiesSet();
            });
        }
    }

    /** 查询并映射结果。 */
    public <T> List<T> query(String cypher, Map<String, Object> parameters, Function<Record, T> mapper) {
        try (Session session = driver.session(sessionConfig)) {
            return session.executeRead(tx -> {
                Result result = parameters == null || parameters.isEmpty()
                        ? tx.run(cypher) : tx.run(cypher, parameters);
                List<T> rows = new ArrayList<>();
                for (Record record : result.list()) {
                    rows.add(mapper.apply(record));
                }
                return rows;
            });
        }
    }

    /** 查询单个数值（计数等）。 */
    public long count(String cypher) {
        List<Long> values = query(cypher, Map.of(), record -> record.get("cnt").asLong());
        return values.isEmpty() ? 0L : values.get(0);
    }

    /** 连通性检查（启动自检用）。 */
    public boolean isReachable() {
        try {
            driver.verifyConnectivity();
            return true;
        } catch (RuntimeException e) {
            LOG.warn("Neo4j 不可达: {}", e.toString());
            return false;
        }
    }

    @Override
    public void close() {
        if (owned) {
            driver.close();
        }
    }
}
