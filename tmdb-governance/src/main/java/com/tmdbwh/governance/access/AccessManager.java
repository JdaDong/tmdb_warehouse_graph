package com.tmdbwh.governance.access;

import com.tmdbwh.common.clickhouse.ClickHouseSql;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 权限管理（RBAC）。
 *
 * <p>为什么用角色而不是直接给用户授权：数仓的岗位是稳定的（分析师、开发、管理员），
 * 人员是流动的。按角色授权后，人员变动只需要"改用户的角色"，不需要重新梳理每张表的权限，
 * 也避免出现"某离职同事还留着一堆表权限"的情况。
 *
 * <p>语句使用 {@code IF NOT EXISTS} / {@code IF EXISTS}，可重复执行（幂等）。
 */
public final class AccessManager {

    private final Map<String, List<String>> roles;
    private final String cluster;

    private AccessManager(Map<String, List<String>> roles, String cluster) {
        this.roles = roles;
        this.cluster = cluster == null ? "" : cluster;
    }

    /** 从默认配置加载。 */
    public static AccessManager load(String cluster) {
        return load(ConfigFactory.load().getConfig("tmdbwh.governance.access"), cluster);
    }

    /** 从指定配置加载。 */
    public static AccessManager load(Config config, String cluster) {
        Map<String, List<String>> roles = new LinkedHashMap<>();
        Config roleConfig = config.getConfig("roles");
        for (String role : roleConfig.root().keySet()) {
            roles.put(role, new ArrayList<>(roleConfig.getStringList(role + ".grants")));
        }
        return new AccessManager(roles, cluster);
    }

    /**
     * 生成全部授权语句（建角色 + 授权）。
     *
     * <p>授权项格式：{@code <库.表>:<权限>}，例如 {@code dws.*:SELECT}、{@code *.*:ALL}。
     */
    public List<String> plan() {
        List<String> statements = new ArrayList<>();
        String onCluster = ClickHouseSql.onCluster(cluster);
        for (Map.Entry<String, List<String>> entry : roles.entrySet()) {
            statements.add("CREATE ROLE IF NOT EXISTS " + ClickHouseSql.identifier(entry.getKey()) + onCluster);
            for (String grant : entry.getValue()) {
                statements.add(grantStatement(entry.getKey(), grant, onCluster));
            }
        }
        return statements;
    }

    /**
     * 解析单个授权项。
     *
     * @param grant 形如 {@code dws.*:SELECT}
     */
    public static String grantStatement(String role, String grant, String onCluster) {
        int colon = grant.lastIndexOf(':');
        if (colon <= 0) {
            throw new IllegalArgumentException("授权项格式应为 <库.表>:<权限>，实际: " + grant);
        }
        String target = grant.substring(0, colon);
        String privilege = grant.substring(colon + 1).toUpperCase(Locale.ROOT);
        String database;
        String table;
        int dot = target.indexOf('.');
        if (dot < 0) {
            database = target;
            table = "*";
        } else {
            database = target.substring(0, dot);
            table = target.substring(dot + 1);
        }
        return "GRANT " + privilege + " ON " + ClickHouseSql.identifier(database) + "."
                + ("*".equals(table) ? "*" : ClickHouseSql.identifier(table))
                + " TO " + ClickHouseSql.identifier(role) + onCluster;
    }

    /** 为用户绑定角色（幂等）。 */
    public static String grantRoleToUser(String role, String user, String onCluster) {
        return "GRANT " + ClickHouseSql.identifier(role) + " TO " + ClickHouseSql.identifier(user)
                + (onCluster == null ? "" : onCluster);
    }

    /** 回收角色在某张表上的权限（离职 / 岗位调整时使用）。 */
    public static String revoke(String role, String database, String table, String privilege, String onCluster) {
        return "REVOKE " + privilege.toUpperCase(Locale.ROOT) + " ON " + ClickHouseSql.identifier(database) + "."
                + ClickHouseSql.identifier(table) + " FROM " + ClickHouseSql.identifier(role)
                + (onCluster == null ? "" : onCluster);
    }

    public Map<String, List<String>> getRoles() {
        return Map.copyOf(roles);
    }

    public List<String> grantsOf(String role) {
        return List.copyOf(roles.getOrDefault(Objects.requireNonNull(role, "role"), List.of()));
    }
}
