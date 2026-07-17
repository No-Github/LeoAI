package org.leo.core.puppet.database;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaDatabaseConnectionAdapterTest {

    private final JavaDatabaseConnectionAdapter adapter = new JavaDatabaseConnectionAdapter();

    @Test
    void createsJdbcParametersFromCanonicalMysqlConnection() {
        Map<String, Object> result = adapter.adapt(DatabaseConnectionSpec.fromMap(Map.of(
                "type", "mysql", "host", "db.internal", "port", 3307, "database", "inventory",
                "username", "app", "password", "secret", "options", Map.of("useSSL", false))));

        assertEquals("jdbc", result.get("provider"));
        assertEquals("com.mysql.cj.jdbc.Driver", result.get("driverClass"));
        assertTrue(String.valueOf(result.get("jdbcUrl")).startsWith("jdbc:mysql://db.internal:3307/inventory?"));
        assertEquals("app", result.get("username"));
    }

    @Test
    void supportsOracleSidAndExplicitJavaOverride() {
        Map<String, Object> oracle = adapter.adapt(DatabaseConnectionSpec.fromMap(Map.of(
                "type", "oracle", "variant", "sid", "host", "oracle.internal", "sid", "ORCL")));
        assertEquals("jdbc:oracle:thin:@oracle.internal:1521:ORCL", oracle.get("jdbcUrl"));

        Map<String, Object> overridden = adapter.adapt(DatabaseConnectionSpec.fromMap(Map.of(
                "type", "sqlite", "file", "/tmp/default.sqlite", "nativeOptions", Map.of("java", Map.of(
                        "driverClass", "custom.Driver", "jdbcUrl", "jdbc:custom:value")))));
        assertEquals("custom.Driver", overridden.get("driverClass"));
        assertEquals("jdbc:custom:value", overridden.get("jdbcUrl"));
    }
}
