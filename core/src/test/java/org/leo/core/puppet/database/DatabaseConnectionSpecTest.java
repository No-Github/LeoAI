package org.leo.core.puppet.database;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseConnectionSpecTest {

    @Test
    void parsesCanonicalConnectionWithoutRuntimeTechnology() {
        DatabaseConnectionSpec spec = DatabaseConnectionSpec.fromMap(Map.of(
                "type", "mysql", "variant", "default", "host", "db.internal", "port", 3306,
                "database", "inventory", "username", "app", "password", "secret",
                "options", Map.of("charset", "utf8mb4")));

        assertEquals("mysql", spec.getType());
        assertEquals("db.internal", spec.getHost());
        assertEquals("inventory", spec.getDatabase());
        assertTrue(spec.getNativeOptions().isEmpty());
    }

    @Test
    void isolatesLegacyJdbcValuesAsJavaOnlyOverrides() {
        DatabaseConnectionSpec spec = DatabaseConnectionSpec.fromMap(Map.of(
                "type", "sqlite", "file", "/tmp/example.sqlite",
                "url", "jdbc:sqlite:/tmp/example.sqlite", "driver", "org.sqlite.JDBC"));

        assertEquals("jdbc:sqlite:/tmp/example.sqlite", spec.nativeOptions("java").get("jdbcUrl"));
        assertTrue(spec.nativeOptions("php").isEmpty());
    }

    @Test
    void validatesRequiredRuntimeNeutralLocator() {
        assertThrows(IllegalArgumentException.class,
                () -> DatabaseConnectionSpec.fromMap(Map.of("type", "sqlite")));
    }
}
