package com.tmdbwh.governance.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.Test;

/** RBAC 授权语句生成。 */
class AccessManagerTest {

    private static AccessManager manager() {
        return AccessManager.load(ConfigFactory.parseString(
                "roles = {"
                        + "analyst = { grants = [\"dws.*:SELECT\", \"ads.*:SELECT\"] },"
                        + "engineer = { grants = [\"*.*:SELECT\"] }"
                        + "}"), "");
    }

    @Test
    void planCreatesRolesAndGrants() {
        assertThat(manager().plan())
                .anySatisfy(statement -> assertThat(statement).contains("CREATE ROLE IF NOT EXISTS analyst"))
                .anySatisfy(statement -> assertThat(statement).contains("GRANT SELECT ON dws.* TO analyst"));
    }

    @Test
    void grantsAreParsedFromConfig() {
        assertThat(manager().grantsOf("analyst")).containsExactly("dws.*:SELECT", "ads.*:SELECT");
    }

    @Test
    void grantStatementHandlesWildcards() {
        assertThat(AccessManager.grantStatement("engineer", "*.*:ALL", ""))
                .isEqualTo("GRANT ALL ON *.* TO engineer");
    }

    @Test
    void grantStatementHandlesSingleTable() {
        assertThat(AccessManager.grantStatement("analyst", "dwd.dim_movie:SELECT", ""))
                .isEqualTo("GRANT SELECT ON dwd.dim_movie TO analyst");
    }

    @Test
    void grantStatementUppercasesPrivilege() {
        assertThat(AccessManager.grantStatement("analyst", "dws.*:select", "")).contains("GRANT SELECT");
    }

    @Test
    void malformedGrantIsRejected() {
        assertThatThrownBy(() -> AccessManager.grantStatement("analyst", "dws.SELECT", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("授权项格式");
    }

    @Test
    void clusterGrantsCarryOnCluster() {
        assertThat(AccessManager.grantStatement("analyst", "dws.*:SELECT", " ON CLUSTER tmdb_cluster"))
                .endsWith("TO analyst ON CLUSTER tmdb_cluster");
    }

    @Test
    void userRoleBindingIsGenerated() {
        assertThat(AccessManager.grantRoleToUser("analyst", "alice", ""))
                .isEqualTo("GRANT analyst TO alice");
    }

    @Test
    void revokeIsGenerated() {
        assertThat(AccessManager.revoke("analyst", "dws", "dim_movie", "select", ""))
                .isEqualTo("REVOKE SELECT ON dws.dim_movie FROM analyst");
    }

    @Test
    void rolesAreExposed() {
        assertThat(manager().getRoles()).containsKeys("analyst", "engineer");
        assertThat(manager().grantsOf("unknown")).isEmpty();
    }
}
