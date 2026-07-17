package org.leo.core.puppet.database;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runtime-neutral database connection description.
 *
 * <p>The shared SQL management layer owns database semantics only. JDBC and PDO
 * details belong to their runtime modules and may be supplied explicitly through
 * {@code nativeOptions} for advanced, runtime-specific overrides.</p>
 */
public final class DatabaseConnectionSpec {

    private final String type;
    private final String variant;
    private final String host;
    private final Integer port;
    private final String database;
    private final String service;
    private final String sid;
    private final String file;
    private final String username;
    private final String password;
    private final Integer timeoutSeconds;
    private final Map<String, Object> options;
    private final Map<String, Object> nativeOptions;

    public DatabaseConnectionSpec(String type,
                                  String variant,
                                  String host,
                                  Integer port,
                                  String database,
                                  String service,
                                  String sid,
                                  String file,
                                  String username,
                                  String password,
                                  Integer timeoutSeconds,
                                  Map<String, Object> options,
                                  Map<String, Object> nativeOptions) {
        this.type = normalize(type);
        this.variant = normalize(variant);
        this.host = trimToNull(host);
        this.port = port;
        this.database = trimToNull(database);
        this.service = trimToNull(service);
        this.sid = trimToNull(sid);
        this.file = trimToNull(file);
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        this.timeoutSeconds = timeoutSeconds;
        this.options = immutableCopy(options);
        this.nativeOptions = immutableCopy(nativeOptions);
        validate();
    }

    public static DatabaseConnectionSpec fromMap(Map<String, Object> source) {
        if (source == null) throw new IllegalArgumentException("connection 不能为空");
        Map<String, Object> nativeOptions = map(source.get("nativeOptions"));

        // Compatibility is deliberately isolated here. Legacy JDBC fields are
        // recorded only as a Java override and never interpreted by PHP.
        String legacyUrl = string(source.get("url"));
        String legacyDriver = string(source.get("driver"));
        if (!legacyUrl.isBlank() || !legacyDriver.isBlank()) {
            Map<String, Object> mergedNative = new LinkedHashMap<String, Object>(nativeOptions);
            Map<String, Object> java = new LinkedHashMap<String, Object>(map(mergedNative.get("java")));
            if (!legacyUrl.isBlank()) java.putIfAbsent("jdbcUrl", legacyUrl);
            if (!legacyDriver.isBlank()) java.putIfAbsent("driverClass", legacyDriver);
            mergedNative.put("java", java);
            nativeOptions = mergedNative;
        }

        return new DatabaseConnectionSpec(
                string(source.get("type")),
                string(source.get("variant")),
                string(source.get("host")),
                integer(source.get("port")),
                firstString(source, "database", "databaseName"),
                string(source.get("service")),
                string(source.get("sid")),
                firstString(source, "file", "path"),
                firstString(source, "username", "user"),
                string(source.get("password")),
                integer(source.get("timeoutSeconds")),
                map(source.get("options")),
                nativeOptions
        );
    }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("type", type);
        result.put("variant", variant);
        result.put("host", host);
        result.put("port", port);
        result.put("database", database);
        result.put("service", service);
        result.put("sid", sid);
        result.put("file", file);
        result.put("username", username);
        result.put("password", password);
        result.put("timeoutSeconds", timeoutSeconds);
        result.put("options", options);
        result.put("nativeOptions", nativeOptions);
        return result;
    }

    public Map<String, Object> nativeOptions(String runtime) {
        return map(nativeOptions.get(normalize(runtime)));
    }

    public String getType() { return type; }
    public String getVariant() { return variant; }
    public String getHost() { return host; }
    public Integer getPort() { return port; }
    public String getDatabase() { return database; }
    public String getService() { return service; }
    public String getSid() { return sid; }
    public String getFile() { return file; }
    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public Integer getTimeoutSeconds() { return timeoutSeconds; }
    public Map<String, Object> getOptions() { return options; }
    public Map<String, Object> getNativeOptions() { return nativeOptions; }

    private void validate() {
        if (type.isBlank()) throw new IllegalArgumentException("connection.type 不能为空");
        if ("sqlite".equals(type)) {
            if (file == null && string(nativeOptions("java").get("jdbcUrl")).isBlank()
                    && string(nativeOptions("php").get("dsn")).isBlank()) {
                throw new IllegalArgumentException("SQLite connection.file 不能为空");
            }
            return;
        }
        boolean nativeOnly = !nativeOptions("java").isEmpty() || !nativeOptions("php").isEmpty();
        if (host == null && !nativeOnly) throw new IllegalArgumentException("connection.host 不能为空");
        if (port != null && (port < 1 || port > 65535)) throw new IllegalArgumentException("connection.port 无效");
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> value) {
        if (value == null || value.isEmpty()) return Collections.emptyMap();
        return Collections.unmodifiableMap(new LinkedHashMap<String, Object>(value));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Collections.emptyMap();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static String firstString(Map<String, Object> source, String first, String second) {
        String value = string(source.get(first));
        return value.isBlank() ? string(source.get(second)) : value;
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) return number.intValue();
        String text = string(value);
        if (text.isBlank()) return null;
        try { return Integer.valueOf(text); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("无效整数: " + text); }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String trimToNull(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        return value.trim();
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
