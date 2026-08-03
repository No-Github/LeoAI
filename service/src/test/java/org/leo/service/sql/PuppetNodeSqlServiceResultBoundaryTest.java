package org.leo.service.sql;

import org.junit.jupiter.api.Test;
import org.leo.core.puppet.capability.SqlCapable;
import org.leo.core.puppet.database.DatabaseConnectionSpec;
import org.leo.service.sql.dialect.SqlDialectRegistry;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PuppetNodeSqlServiceResultBoundaryTest {

    @Test
    void exposesRuntimeResultBoundariesToApiCallers() throws Exception {
        PuppetNodeSqlService service = new PuppetNodeSqlService(new SqlDialectRegistry());
        SqlCapable puppet = new SqlCapable() {
            @Override
            public Map<String, Object> executeSql(DatabaseConnectionSpec connection, String sqlScript) {
                return Map.of(
                        "code", 200,
                        "columns", List.of(Map.of("name", "value", "label", "value", "type", "TEXT")),
                        "rows", List.of(Map.of("value", "partial")),
                        "rowCount", 1,
                        "affectedRows", 0,
                        "generatedKey", "42",
                        "truncated", true,
                        "truncationReason", "MAX_RESULT_BYTES",
                        "resultBytes", 2048,
                        "runtimeMetadata", Map.of("provider", "jdbc"));
            }
        };

        Map<String, Object> result = service.executeSql(puppet,
                Map.of("dialect", "sqlite", "connectionMode", "standard", "file", ":memory:"),
                "SELECT value");

        assertEquals(1, result.get("rowCount"));
        assertEquals("42", result.get("generatedKey"));
        assertEquals(true, result.get("truncated"));
        assertEquals("MAX_RESULT_BYTES", result.get("truncationReason"));
        assertEquals(2048, result.get("resultBytes"));
        assertEquals("jdbc", ((Map<?, ?>) result.get("runtimeMetadata")).get("provider"));
    }

    @Test
    void genericDialectUsesConfiguredHealthCheckAndRejectsStructuredMetadata() throws Exception {
        PuppetNodeSqlService service = new PuppetNodeSqlService(new SqlDialectRegistry());
        String[] executedSql = new String[1];
        SqlCapable puppet = (connection, sqlScript) -> {
            executedSql[0] = sqlScript;
            return Map.of("code", 200, "rows", List.of(Map.of("status", "ok")));
        };
        Map<String, Object> connection = Map.of(
                "dialect", "generic",
                "connectionMode", "custom",
                "dialectOptions", Map.of("testSql", "VALUES 'ok'"),
                "runtimeOptions", Map.of("java", Map.of(
                        "driverClass", "com.vendor.Driver",
                        "jdbcUrl", "jdbc:vendor:test")));

        Map<String, Object> result = service.testConnection(puppet, connection);

        assertEquals("VALUES 'ok'", executedSql[0]);
        assertEquals("ok", result.get("databaseVersion"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.getDatabases(puppet, connection));
        assertEquals("Generic SQL 方言不支持能力 listDatabases，请直接执行厂商 SQL", error.getMessage());
    }

    @Test
    void canonicalizesDialectAliasesBeforeRuntimeInspectionAndExecution() throws Exception {
        PuppetNodeSqlService service = new PuppetNodeSqlService(new SqlDialectRegistry());
        String[] inspectedDialect = new String[1];
        String[] executedDialect = new String[1];
        SqlCapable puppet = new SqlCapable() {
            @Override
            public Map<String, Object> inspectDatabaseRuntime(Map<String, Object> connection) {
                inspectedDialect[0] = String.valueOf(connection.get("dialect"));
                return Map.of("code", 501, "available", true, "msg", "inspection unsupported");
            }

            @Override
            public Map<String, Object> executeSql(DatabaseConnectionSpec connection, String sqlScript) {
                executedDialect[0] = connection.getDialect();
                return Map.of("code", 200, "rows", List.of(Map.of("version", "DM8")));
            }
        };

        Map<String, Object> result = service.testConnection(puppet,
                Map.of("dialect", "dameng", "connectionMode", "standard",
                        "host", "dm.internal", "port", 5236, "database", "APP"));

        assertEquals(true, result.get("success"));
        assertEquals("dm", inspectedDialect[0]);
        assertEquals("dm", executedDialect[0]);
    }

    @Test
    void unavailableDriverStopsBeforeConnectionAndReturnsActionableDiagnostics() throws Exception {
        PuppetNodeSqlService service = new PuppetNodeSqlService(new SqlDialectRegistry());
        AtomicBoolean executed = new AtomicBoolean();
        SqlCapable puppet = new SqlCapable() {
            @Override
            public Map<String, Object> executeSql(DatabaseConnectionSpec connection, String sqlScript) {
                executed.set(true);
                return Map.of("code", 200);
            }

            @Override
            public Map<String, Object> inspectDatabaseRuntime(Map<String, Object> connection) {
                return Map.of(
                        "code", 200,
                        "runtime", "java",
                        "provider", "jdbc",
                        "available", true,
                        "requestedDriver", Map.of(
                                "id", "missing.Driver",
                                "available", false,
                                "message", "JDBC 驱动类不可用"));
            }
        };

        Map<String, Object> result = service.testConnection(puppet,
                Map.of("dialect", "sqlite", "connectionMode", "standard", "file", ":memory:"));

        assertEquals(false, result.get("success"));
        assertEquals("driver", result.get("failureStage"));
        assertEquals("DRIVER_NOT_FOUND", result.get("errorCategory"));
        assertEquals(false, executed.get());
        assertEquals("failed", ((Map<?, ?>) ((List<?>) result.get("diagnostics")).get(0))
                .get("status"));
    }

    @Test
    void classifiesProviderAuthenticationAndNetworkFailuresByStage() throws Exception {
        PuppetNodeSqlService service = new PuppetNodeSqlService(new SqlDialectRegistry());
        SqlCapable missingProvider = new SqlCapable() {
            @Override
            public Map<String, Object> executeSql(DatabaseConnectionSpec connection, String sqlScript) {
                throw new AssertionError("provider preflight must stop execution");
            }

            @Override
            public Map<String, Object> inspectDatabaseRuntime(Map<String, Object> connection) {
                return Map.of("code", 200, "runtime", "php", "provider", "pdo",
                        "available", false, "msg", "PDO extension is missing");
            }
        };
        Map<String, Object> providerResult = service.testConnection(missingProvider,
                Map.of("dialect", "sqlite", "connectionMode", "standard", "file", ":memory:"));
        assertEquals("PROVIDER_NOT_FOUND", providerResult.get("errorCategory"));
        assertEquals("driver", providerResult.get("failureStage"));

        Map<String, Object> connection = Map.of(
                "dialect", "sqlite", "connectionMode", "standard", "file", ":memory:");
        SqlCapable authenticationFailure = (spec, sql) -> Map.of(
                "code", 401, "errorCategory", "AUTHENTICATION_FAILED",
                "msg", "invalid credentials");
        Map<String, Object> authenticationResult =
                service.testConnection(authenticationFailure, connection);
        assertEquals("authentication", authenticationResult.get("failureStage"));

        SqlCapable networkFailure = (spec, sql) -> Map.of(
                "code", 503, "errorCategory", "CONNECTION_TIMEOUT",
                "msg", "connection timed out");
        Map<String, Object> networkResult = service.testConnection(networkFailure, connection);
        assertEquals("network", networkResult.get("failureStage"));
    }
}
