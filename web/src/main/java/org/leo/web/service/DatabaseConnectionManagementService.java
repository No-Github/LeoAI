package org.leo.web.service;

import org.leo.core.entity.PuppetDatabaseConnection;
import org.leo.core.entity.User;
import org.leo.core.puppet.database.DatabaseConnectionSpec;
import org.leo.service.PuppetDatabaseConnectionService;
import org.leo.web.exception.ApiException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Application service for persisted database connection profile management. */
@Service
public final class DatabaseConnectionManagementService {

    private final PuppetDatabaseConnectionService connectionService;

    public DatabaseConnectionManagementService(PuppetDatabaseConnectionService connectionService) {
        this.connectionService = connectionService;
    }

    public Map<String, Object> save(User user, String puppetId, Map<String, Object> params) {
        requireUser(user);
        if (puppetId == null || puppetId.isBlank()) {
            throw ApiException.badRequest("Puppet ID 不能为空");
        }
        if (params == null) {
            throw ApiException.badRequest("params不能为空");
        }

        String connectionId = text(params.get("connectionId"));
        PuppetDatabaseConnection existing = connectionId == null
                ? null : connectionService.findById(connectionId);
        if (connectionId != null && existing == null) {
            throw ApiException.notFound("数据库连接不存在");
        }
        if (existing != null && !Objects.equals(existing.getPuppetId(), puppetId)) {
            throw ApiException.forbidden("数据库连接配置与当前 Puppet 不匹配");
        }

        PuppetDatabaseConnection connection = existing == null
                ? newConnection(user, puppetId) : existing;
        DatabaseConnectionSpec spec = connectionSpec(params, existing);
        connectionService.applyConnectionSpec(connection, spec);
        applyMetadata(connection, params, connectionId, spec);

        if (!connectionService.saveOrUpdate(connection)) {
            throw ApiException.serverError("保存数据库连接失败");
        }
        return view(connection);
    }

    public List<Map<String, Object>> listByPuppet(String puppetId, User user) {
        requireUser(user);
        if (puppetId == null || puppetId.isBlank()) {
            throw ApiException.badRequest("Puppet ID 不能为空");
        }
        return connectionService.findByPuppetId(puppetId).stream()
                .map(this::view)
                .toList();
    }

    public void delete(String connectionId, String puppetId, User user) {
        PuppetDatabaseConnection connection = requirePuppetConnection(connectionId, puppetId, user);
        if (!connectionService.deleteById(connection.getConnectionId(), puppetId)) {
            throw ApiException.serverError("删除数据库连接失败");
        }
    }

    public Map<String, Object> setEnabled(String connectionId, String puppetId, boolean enabled, User user) {
        PuppetDatabaseConnection connection = requirePuppetConnection(connectionId, puppetId, user);
        if (!connectionService.setEnabled(connection.getConnectionId(), puppetId, enabled)) {
            throw ApiException.serverError("更新数据库连接状态失败");
        }
        return Map.of("connectionId", connection.getConnectionId(), "status", enabled ? 1 : 0);
    }

    private PuppetDatabaseConnection newConnection(User user, String puppetId) {
        PuppetDatabaseConnection connection = new PuppetDatabaseConnection();
        connection.setCreateUserId(user.getUserId());
        connection.setPuppetId(puppetId);
        return connection;
    }

    private DatabaseConnectionSpec connectionSpec(Map<String, Object> params,
                                                  PuppetDatabaseConnection existing) {
        Object nested = params.get("connection");
        if (!(nested instanceof Map<?, ?> source)) {
            throw ApiException.badRequest("connection 不能为空");
        }
        Map<String, Object> suppliedValues = new LinkedHashMap<String, Object>();
        source.forEach((key, value) -> suppliedValues.put(String.valueOf(key), value));
        Map<String, Object> values = existing == null
                ? suppliedValues
                : connectionService.mergeProtectedValues(existing, suppliedValues);
        return DatabaseConnectionSpec.fromMap(values);
    }

    private void applyMetadata(PuppetDatabaseConnection connection,
                               Map<String, Object> params,
                               String connectionId,
                               DatabaseConnectionSpec spec) {
        String connectionName = text(params.get("connectionName"));
        if (connectionName == null) {
            connectionName = generateName(spec);
        }
        if (connectionService.existsByName(connection.getPuppetId(), connectionName, connectionId)) {
            connectionName = connectionName + "_" + System.currentTimeMillis();
        }
        connection.setConnectionName(connectionName);

        Integer status = optionalInteger(params.get("status"), "status");
        if (status != null) {
            if (status != 0 && status != 1) throw ApiException.badRequest("status必须是0或1");
            connection.setStatus(status);
        }
        Integer maxConnections = optionalInteger(params.get("maxConnections"), "maxConnections");
        if (maxConnections != null) {
            if (maxConnections < 1) throw ApiException.badRequest("maxConnections必须大于0");
            connection.setMaxConnections(maxConnections);
        }
        Integer timeoutSeconds = optionalInteger(params.get("timeoutSeconds"), "timeoutSeconds");
        if (timeoutSeconds != null) {
            if (timeoutSeconds < 1) throw ApiException.badRequest("timeoutSeconds必须大于0");
            connection.setTimeoutSeconds(timeoutSeconds);
        }
        connection.setDescription(nullableText(params.get("description")));
        connection.setRemark(nullableText(params.get("remark")));
    }

    private PuppetDatabaseConnection requirePuppetConnection(String connectionId,
                                                             String puppetId,
                                                             User user) {
        requireUser(user);
        if (connectionId == null || connectionId.isBlank()) {
            throw ApiException.badRequest("connectionId不能为空");
        }
        if (puppetId == null || puppetId.isBlank()) {
            throw ApiException.badRequest("Puppet ID 不能为空");
        }
        PuppetDatabaseConnection connection = connectionService.findById(connectionId.trim());
        if (connection == null) throw ApiException.notFound("数据库连接不存在");
        if (!Objects.equals(connection.getPuppetId(), puppetId)) {
            throw ApiException.forbidden("数据库连接配置与当前 Puppet 不匹配");
        }
        return connection;
    }

    private Map<String, Object> view(PuppetDatabaseConnection connection) {
        return connectionService.toConnectionView(connection);
    }

    private String generateName(DatabaseConnectionSpec connection) {
        String locator = connection.getDatabase();
        if ("sqlite".equals(connection.getDialect())) locator = connection.getFile();
        else if ("oracle".equals(connection.getDialect())) {
            locator = "sid".equals(connection.getVariant()) ? connection.getSid() : connection.getService();
        }
        if (locator == null || locator.isBlank()) locator = connection.getHost();
        if (locator == null || locator.isBlank()) locator = "connection";
        int slash = Math.max(locator.lastIndexOf('/'), locator.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < locator.length()) locator = locator.substring(slash + 1);
        return (connection.getDialect() + "_" + locator).replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private Integer optionalInteger(Object value, String name) {
        if (value == null) return null;
        if (value instanceof Number number) return number.intValue();
        String text = text(value);
        if (text == null) return null;
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException error) {
            throw ApiException.badRequest(name + "必须是整数");
        }
    }

    private String nullableText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private void requireUser(User user) {
        if (user == null || user.getUserId() == null || user.getUserId().isBlank()) {
            throw ApiException.unauthorized("用户未登录");
        }
    }
}
