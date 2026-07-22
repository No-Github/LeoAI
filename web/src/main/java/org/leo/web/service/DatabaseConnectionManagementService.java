package org.leo.web.service;

import org.leo.core.entity.PuppetDatabaseConnection;
import org.leo.core.entity.User;
import org.leo.core.puppet.database.DatabaseConnectionSpec;
import org.leo.service.PuppetDatabaseConnectionService;
import org.leo.web.exception.ApiException;
import org.leo.web.security.DatabaseConnectionPermissionPolicy;
import org.leo.web.security.PermissionPolicy;
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

        String connectionId = firstNonBlank(params, "connectionId", "connId");
        PuppetDatabaseConnection existing = connectionId == null
                ? null : connectionService.findById(connectionId);
        if (connectionId != null && existing == null) {
            throw ApiException.notFound("数据库连接不存在");
        }
        if (existing != null && !DatabaseConnectionPermissionPolicy.canManage(existing, user)) {
            throw ApiException.forbidden("无权限修改此数据库连接");
        }
        if (existing != null && !Objects.equals(existing.getPuppetId(), puppetId)) {
            throw ApiException.forbidden("数据库连接配置与当前 Puppet 不匹配");
        }

        PuppetDatabaseConnection connection = existing == null
                ? newConnection(user, puppetId) : existing;
        applyScope(connection, existing, user, params);
        DatabaseConnectionSpec spec = connectionSpec(params);
        connectionService.applyConnectionSpec(connection, spec);
        applyMetadata(connection, params, connectionId, spec);

        if (!connectionService.saveOrUpdate(connection)) {
            throw ApiException.serverError("保存数据库连接失败");
        }
        return view(connection, user);
    }

    public List<Map<String, Object>> listVisible(String puppetId, User user) {
        requireUser(user);
        if (puppetId == null || puppetId.isBlank()) {
            throw ApiException.badRequest("Puppet ID 不能为空");
        }
        return connectionService.findByPuppetId(puppetId).stream()
                .filter(connection -> DatabaseConnectionPermissionPolicy.canView(connection, user))
                .map(connection -> view(connection, user))
                .toList();
    }

    public void delete(String connectionId, User user) {
        PuppetDatabaseConnection connection = requireManageable(connectionId, user, "删除");
        if (!connectionService.deleteById(connection.getConnectionId())) {
            throw ApiException.serverError("删除数据库连接失败");
        }
    }

    public Map<String, Object> setEnabled(String connectionId, boolean enabled, User user) {
        PuppetDatabaseConnection connection = requireManageable(connectionId, user, "修改此数据库连接状态");
        if (!connectionService.setEnabled(connection.getConnectionId(), enabled)) {
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

    private void applyScope(PuppetDatabaseConnection connection,
                            PuppetDatabaseConnection existing,
                            User user,
                            Map<String, Object> params) {
        String requestedScope = params.containsKey("scope")
                ? text(params.get("scope")) : existing == null ? null : existing.getScope();
        if (requestedScope == null && Boolean.TRUE.equals(params.get("isPublic"))) {
            requestedScope = DatabaseConnectionPermissionPolicy.SCOPE_TEAM;
        }
        String scope = DatabaseConnectionPermissionPolicy.normalizeScope(requestedScope);
        if (DatabaseConnectionPermissionPolicy.SCOPE_PUBLIC.equals(scope)
                && !PermissionPolicy.isAdmin(user)) {
            throw ApiException.forbidden("只有管理员可以设置公开数据库连接");
        }
        if (existing != null
                && !DatabaseConnectionPermissionPolicy.isOwner(existing, user)
                && !PermissionPolicy.isAdmin(user)
                && (!DatabaseConnectionPermissionPolicy.SCOPE_TEAM.equals(scope)
                || !Objects.equals(existing.getTeamId(), user.getTeamId()))) {
            throw ApiException.forbidden("团队管理员只能维护本团队范围的数据库连接");
        }

        DatabaseConnectionPermissionPolicy.ScopeAssignment assignment =
                DatabaseConnectionPermissionPolicy.resolveScopeAssignment(
                        existing, user, scope, text(params.get("teamId")));
        connection.setScope(assignment.scope());
        connection.setTeamId(assignment.teamId());
        connection.setIsPublic(assignment.publicFlag());
    }

    private DatabaseConnectionSpec connectionSpec(Map<String, Object> params) {
        Object nested = params.get("connection");
        if (!(nested instanceof Map<?, ?> source)) {
            throw ApiException.badRequest("connection 不能为空");
        }
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        source.forEach((key, value) -> values.put(String.valueOf(key), value));
        return DatabaseConnectionSpec.fromMap(values);
    }

    private void applyMetadata(PuppetDatabaseConnection connection,
                               Map<String, Object> params,
                               String connectionId,
                               DatabaseConnectionSpec spec) {
        String connectionName = firstNonBlank(params, "connectionName", "connName");
        if (connectionName == null) {
            connectionName = generateName(spec);
        }
        if (connectionService.existsByName(connectionName, connectionId)) {
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

    private PuppetDatabaseConnection requireManageable(String connectionId,
                                                        User user,
                                                        String operation) {
        requireUser(user);
        if (connectionId == null || connectionId.isBlank()) {
            throw ApiException.badRequest("connectionId不能为空");
        }
        PuppetDatabaseConnection connection = connectionService.findById(connectionId.trim());
        if (connection == null) throw ApiException.notFound("数据库连接不存在");
        if (!DatabaseConnectionPermissionPolicy.canManage(connection, user)) {
            throw ApiException.forbidden("无权限" + operation);
        }
        return connection;
    }

    private Map<String, Object> view(PuppetDatabaseConnection connection, User user) {
        Map<String, Object> view = connectionService.toConnectionView(connection);
        view.put("canManage", DatabaseConnectionPermissionPolicy.canManage(connection, user));
        view.put("ownedByCurrentUser", DatabaseConnectionPermissionPolicy.isOwner(connection, user));
        return view;
    }

    private String generateName(DatabaseConnectionSpec connection) {
        String locator = connection.getDatabase();
        if ("sqlite".equals(connection.getType())) locator = connection.getFile();
        else if ("oracle".equals(connection.getType())) {
            locator = "sid".equals(connection.getVariant()) ? connection.getSid() : connection.getService();
        }
        if (locator == null || locator.isBlank()) locator = connection.getHost();
        if (locator == null || locator.isBlank()) locator = "connection";
        int slash = Math.max(locator.lastIndexOf('/'), locator.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < locator.length()) locator = locator.substring(slash + 1);
        return (connection.getType() + "_" + locator).replaceAll("[^A-Za-z0-9_.-]", "_");
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

    private String firstNonBlank(Map<String, Object> params, String primary, String compatibility) {
        String value = text(params.get(primary));
        return value == null ? text(params.get(compatibility)) : value;
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
