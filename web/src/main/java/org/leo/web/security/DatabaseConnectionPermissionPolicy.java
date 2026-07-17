package org.leo.web.security;

import org.leo.core.entity.PuppetDatabaseConnection;
import org.leo.core.entity.User;

import java.util.Locale;
import java.util.Objects;

/** Permission rules for database connection metadata, management and credential use. */
public final class DatabaseConnectionPermissionPolicy {

    public static final String SCOPE_PRIVATE = "private";
    public static final String SCOPE_TEAM = "team";
    public static final String SCOPE_PUBLIC = "public";

    private DatabaseConnectionPermissionPolicy() {
    }

    public static String normalizeScope(String scope) {
        String normalized = scope == null ? "" : scope.trim().toLowerCase(Locale.ROOT);
        if (SCOPE_TEAM.equals(normalized) || SCOPE_PUBLIC.equals(normalized)) return normalized;
        return SCOPE_PRIVATE;
    }

    /** Resolves persisted scope fields after the caller has checked management permission. */
    public static ScopeAssignment resolveScopeAssignment(PuppetDatabaseConnection existing,
                                                         User user,
                                                         String requestedScope,
                                                         String requestedTeamId) {
        if (user == null) throw new IllegalArgumentException("用户未登录");
        String scope = normalizeScope(requestedScope);
        if (!SCOPE_TEAM.equals(scope)) {
            return new ScopeAssignment(scope, null);
        }

        String teamId;
        if (PermissionPolicy.isAdmin(user)) {
            String existingTeamId = existing != null
                    && SCOPE_TEAM.equals(normalizeScope(existing.getScope()))
                    ? existing.getTeamId() : null;
            teamId = firstNonBlank(requestedTeamId,
                    existingTeamId, user.getTeamId());
        } else {
            teamId = firstNonBlank(user.getTeamId());
        }
        if (teamId == null) {
            throw new IllegalArgumentException("团队范围需要先加入团队");
        }
        return new ScopeAssignment(scope, teamId);
    }

    public static boolean canView(PuppetDatabaseConnection connection, User user) {
        if (!hasIdentity(connection, user)) return false;
        if (PermissionPolicy.isAdmin(user) || isOwner(connection, user)) return true;
        String scope = normalizeScope(connection.getScope());
        if (SCOPE_PUBLIC.equals(scope)) return true;
        return SCOPE_TEAM.equals(scope) && sameTeam(connection, user);
    }

    /** Credential material is resolved server-side only after this check succeeds. */
    public static boolean canUseCredentials(PuppetDatabaseConnection connection, User user) {
        return canView(connection, user);
    }

    public static boolean canManage(PuppetDatabaseConnection connection, User user) {
        if (!hasIdentity(connection, user)) return false;
        String scope = normalizeScope(connection.getScope());
        if (SCOPE_PUBLIC.equals(scope)) return PermissionPolicy.isAdmin(user);
        if (PermissionPolicy.isAdmin(user) || isOwner(connection, user)) return true;
        return SCOPE_TEAM.equals(scope) && PermissionPolicy.isLeader(user) && sameTeam(connection, user);
    }

    public static boolean isOwner(PuppetDatabaseConnection connection, User user) {
        return connection != null && user != null
                && Objects.equals(connection.getCreateUserId(), user.getUserId());
    }

    public static boolean sameTeam(PuppetDatabaseConnection connection, User user) {
        return connection != null && user != null
                && user.getTeamId() != null && !user.getTeamId().isBlank()
                && Objects.equals(connection.getTeamId(), user.getTeamId());
    }

    private static boolean hasIdentity(PuppetDatabaseConnection connection, User user) {
        return connection != null && user != null
                && user.getUserId() != null && !user.getUserId().isBlank();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }

    public record ScopeAssignment(String scope, String teamId) {
        public int publicFlag() {
            return SCOPE_PUBLIC.equals(scope) ? 1 : 0;
        }
    }
}
