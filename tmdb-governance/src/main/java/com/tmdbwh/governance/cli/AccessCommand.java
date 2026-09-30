package com.tmdbwh.governance.cli;

import com.tmdbwh.common.clickhouse.ClickHouseClient;
import com.tmdbwh.governance.ConfigLoader;
import com.tmdbwh.governance.access.AccessManager;
import java.util.List;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/** {@code governance access}：RBAC 权限（打印或应用）。 */
@Command(name = "access", mixinStandardHelpOptions = true, sortOptions = false,
        description = "权限：按角色生成授权语句（默认只打印）")
public class AccessCommand implements Callable<Integer> {

    private static final Logger LOG = LoggerFactory.getLogger(AccessCommand.class);

    @ParentCommand
    com.tmdbwh.governance.cli.GovernanceCli parent;

    @Option(names = "--apply", description = "在 ClickHouse 上执行授权语句")
    boolean apply;

    @Option(names = "--user", description = "把角色授予指定用户（配合 --role 使用）")
    String user;

    @Option(names = "--role", description = "要授予用户的角色名")
    String role;

    @Override
    public Integer call() {
        var config = ConfigLoader.load(parent.configFile, "governance-access");
        AccessManager manager = AccessManager.load(config.getClickhouse().getCluster());

        List<String> statements = manager.plan();
        System.out.println("授权语句（共 " + statements.size() + " 条）:");
        statements.forEach(statement -> System.out.println("  " + statement + ";"));

        if (user != null && role != null) {
            String grant = AccessManager.grantRoleToUser(role, user,
                    com.tmdbwh.common.clickhouse.ClickHouseSql.onCluster(config.getClickhouse().getCluster()));
            System.out.println("  " + grant + ";");
            statements = new java.util.ArrayList<>(statements);
            statements.add(grant);
        } else if (user != null || role != null) {
            LOG.error("--user 与 --role 必须同时提供");
            return 2;
        }

        if (!apply) {
            System.out.println("这是预览，未执行；追加 --apply 在数据库上执行。");
            return 0;
        }
        try (ClickHouseClient client = ClickHouseClient.create(config.getClickhouse())) {
            for (String statement : statements) {
                LOG.info("执行: {}", statement);
                client.execute(statement + ";");
            }
            return 0;
        } catch (RuntimeException e) {
            LOG.error("授权执行失败: {}", e.toString());
            return 1;
        }
    }
}
