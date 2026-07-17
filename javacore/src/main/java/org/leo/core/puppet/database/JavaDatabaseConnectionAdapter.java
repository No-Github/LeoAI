package org.leo.core.puppet.database;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

/** Converts the shared connection description into Java/JDBC component input. */
public final class JavaDatabaseConnectionAdapter {

    public Map<String, Object> adapt(DatabaseConnectionSpec connection) {
        Map<String, Object> override = connection.nativeOptions("java");
        String driverClass = text(override.get("driverClass"));
        String jdbcUrl = text(override.get("jdbcUrl"));
        if (driverClass.isBlank()) driverClass = defaultDriver(connection.getType());
        if (jdbcUrl.isBlank()) jdbcUrl = buildUrl(connection);

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("provider", "jdbc");
        result.put("driverClass", driverClass);
        result.put("jdbcUrl", jdbcUrl);
        result.put("username", connection.getUsername());
        result.put("password", connection.getPassword());
        result.put("timeoutSeconds", connection.getTimeoutSeconds());
        return result;
    }

    private String defaultDriver(String type) {
        return switch (type) {
            case "mysql" -> "com.mysql.cj.jdbc.Driver";
            case "postgresql" -> "org.postgresql.Driver";
            case "sqlserver" -> "com.microsoft.sqlserver.jdbc.SQLServerDriver";
            case "oracle" -> "oracle.jdbc.driver.OracleDriver";
            case "sqlite" -> "org.sqlite.JDBC";
            default -> throw new IllegalArgumentException("Java 不支持数据库类型: " + type);
        };
    }

    private String buildUrl(DatabaseConnectionSpec connection) {
        String type = connection.getType();
        if ("sqlite".equals(type)) return "jdbc:sqlite:" + required(connection.getFile(), "connection.file");
        String host = required(connection.getHost(), "connection.host");
        int port = connection.getPort() == null ? defaultPort(type) : connection.getPort();
        String options = query(connection.getOptions());
        return switch (type) {
            case "mysql" -> "jdbc:mysql://" + host + ":" + port + "/"
                    + path(connection.getDatabase()) + suffix(options, "?");
            case "postgresql" -> "jdbc:postgresql://" + host + ":" + port + "/"
                    + path(connection.getDatabase()) + suffix(options, "?");
            case "sqlserver" -> "jdbc:sqlserver://" + host + ":" + port
                    + value(connection.getDatabase(), ";databaseName=") + sqlServerOptions(connection.getOptions());
            case "oracle" -> oracleUrl(connection, host, port) + suffix(options, "?");
            default -> throw new IllegalArgumentException("Java 不支持数据库类型: " + type);
        };
    }

    private String oracleUrl(DatabaseConnectionSpec connection, String host, int port) {
        if ("sid".equals(connection.getVariant()) || connection.getSid() != null) {
            return "jdbc:oracle:thin:@" + host + ":" + port + ":" + required(connection.getSid(), "connection.sid");
        }
        String service = connection.getService() == null ? connection.getDatabase() : connection.getService();
        return "jdbc:oracle:thin:@//" + host + ":" + port + "/" + required(service, "connection.service");
    }

    private int defaultPort(String type) {
        return switch (type) {
            case "mysql" -> 3306;
            case "postgresql" -> 5432;
            case "sqlserver" -> 1433;
            case "oracle" -> 1521;
            default -> throw new IllegalArgumentException("数据库端口不能为空: " + type);
        };
    }

    private String query(Map<String, Object> options) {
        StringJoiner result = new StringJoiner("&");
        options.forEach((key, value) -> {
            if (key != null && !key.isBlank() && value != null) {
                result.add(encode(key) + "=" + encode(String.valueOf(value)));
            }
        });
        return result.toString();
    }

    private String sqlServerOptions(Map<String, Object> options) {
        StringBuilder result = new StringBuilder();
        options.forEach((key, value) -> {
            if (key != null && key.matches("[A-Za-z][A-Za-z0-9_]*") && value != null) {
                result.append(';').append(key).append('=').append(String.valueOf(value).replace(";", ""));
            }
        });
        return result.toString();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String path(String value) { return value == null ? "" : value.replace("/", "%2F"); }
    private String value(String value, String prefix) { return value == null || value.isBlank() ? "" : prefix + value; }
    private String suffix(String value, String prefix) { return value.isBlank() ? "" : prefix + value; }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " 不能为空");
        return value;
    }
}
