package org.leo.core.puppet.service;

import org.leo.core.net.Communication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;
import org.leo.core.puppet.database.DatabaseConnectionSpec;
import org.leo.core.puppet.database.JavaDatabaseConnectionAdapter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SqlService extends ComponentService {

    private final JavaDatabaseConnectionAdapter connectionAdapter = new JavaDatabaseConnectionAdapter();

    public SqlService(Communication communication, List<RequestLayer> requestLayers, List<ResponseLayer> responseLayers) {
        super(communication, requestLayers, responseLayers);
    }

    public Map<String, Object> executeSql(DatabaseConnectionSpec connection, String sqlScript) throws Exception {
        HashMap<String, Object> payload = new HashMap<String, Object>(connectionAdapter.adapt(connection));
        payload.put("sql", sqlScript);
        return invokeComponent("DatabaseComponent", payload);
    }

    public Map<String, Object> inspectRuntime(Map<String, Object> connection) throws Exception {
        HashMap<String, Object> payload = new HashMap<String, Object>();
        payload.put("operation", "capabilities");
        payload.put("requestedDriver", requestedDriver(connection));
        return invokeComponent("DatabaseComponent", payload);
    }

    @SuppressWarnings("unchecked")
    private String requestedDriver(Map<String, Object> connection) {
        if (connection == null) return "";
        Object runtimeOptions = connection.get("runtimeOptions");
        if (runtimeOptions instanceof Map<?, ?> runtimes) {
            Object javaOptions = runtimes.get("java");
            if (javaOptions instanceof Map<?, ?> java) {
                Object configured = java.get("driverClass");
                if (configured != null && !String.valueOf(configured).isBlank()) {
                    return String.valueOf(configured).trim();
                }
            }
        }
        String dialect = String.valueOf(connection.getOrDefault("dialect", "")).trim().toLowerCase();
        return switch (dialect) {
            case "mysql" -> "com.mysql.cj.jdbc.Driver";
            case "postgresql" -> "org.postgresql.Driver";
            case "sqlserver" -> "com.microsoft.sqlserver.jdbc.SQLServerDriver";
            case "oracle" -> "oracle.jdbc.driver.OracleDriver";
            case "sqlite" -> "org.sqlite.JDBC";
            default -> "";
        };
    }
}
