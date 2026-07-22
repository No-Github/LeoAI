package org.leo.web.security;

import org.leo.core.entity.PuppetDatabaseConnection;
import org.leo.core.entity.User;
import org.leo.service.PuppetDatabaseConnectionService;
import org.leo.web.exception.ApiException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves an inline database connection or a persisted profile at the web
 * authorization boundary.
 */
@Component
public final class DatabaseConnectionResolver {

    private static final String CONNECTION_ID = "connectionId";

    private final PuppetDatabaseConnectionService connectionService;

    public DatabaseConnectionResolver(PuppetDatabaseConnectionService connectionService) {
        this.connectionService = connectionService;
    }

    public Map<String, Object> resolve(Map<String, Object> supplied,
                                       String expectedPuppetId,
                                       User currentUser) {
        if (supplied == null) {
            throw ApiException.badRequest("connection 不能为空");
        }
        String connectionId = reference(supplied);
        if (connectionId == null) {
            return new LinkedHashMap<String, Object>(supplied);
        }
        if (currentUser == null) {
            throw ApiException.unauthorized("用户未登录");
        }

        PuppetDatabaseConnection saved = connectionService.findById(connectionId);
        if (saved == null) {
            throw ApiException.notFound("数据库连接配置不存在");
        }
        if (expectedPuppetId == null || expectedPuppetId.isBlank()
                || !Objects.equals(saved.getPuppetId(), expectedPuppetId)) {
            throw ApiException.forbidden("数据库连接配置与当前 Puppet 不匹配");
        }
        if (!DatabaseConnectionPermissionPolicy.canUseCredentials(saved, currentUser)) {
            throw ApiException.forbidden("无权限使用此数据库连接");
        }
        return connectionService.toActiveConnectionSpec(saved).toMap();
    }

    public String reference(Map<String, Object> supplied) {
        if (supplied == null) return null;
        return text(supplied.get(CONNECTION_ID));
    }

    private String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
