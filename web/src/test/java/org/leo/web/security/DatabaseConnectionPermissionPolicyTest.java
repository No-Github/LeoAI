package org.leo.web.security;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.PuppetDatabaseConnection;
import org.leo.core.entity.User;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatabaseConnectionPermissionPolicyTest {

    @Test
    void appliesPrivateTeamAndPublicVisibility() {
        PuppetDatabaseConnection connection = connection("private");

        assertTrue(DatabaseConnectionPermissionPolicy.canView(connection, user("owner", "normal", "team-a")));
        assertFalse(DatabaseConnectionPermissionPolicy.canView(connection, user("member", "normal", "team-a")));

        connection.setScope("team");
        assertTrue(DatabaseConnectionPermissionPolicy.canUseCredentials(
                connection, user("member", "normal", "team-a")));
        assertFalse(DatabaseConnectionPermissionPolicy.canView(
                connection, user("outsider", "normal", "team-b")));

        connection.setScope("public");
        assertTrue(DatabaseConnectionPermissionPolicy.canUseCredentials(
                connection, user("outsider", "normal", "team-b")));
    }

    @Test
    void limitsManagementToOwnerTeamLeaderOrAdminByScope() {
        PuppetDatabaseConnection connection = connection("private");
        assertTrue(DatabaseConnectionPermissionPolicy.canManage(connection, user("owner", "normal", "team-a")));
        assertFalse(DatabaseConnectionPermissionPolicy.canManage(connection, user("leader", "leader", "team-a")));

        connection.setScope("team");
        assertTrue(DatabaseConnectionPermissionPolicy.canManage(connection, user("leader", "leader", "team-a")));
        assertFalse(DatabaseConnectionPermissionPolicy.canManage(connection, user("member", "normal", "team-a")));

        connection.setScope("public");
        assertFalse(DatabaseConnectionPermissionPolicy.canManage(connection, user("owner", "normal", "team-a")));
        assertTrue(DatabaseConnectionPermissionPolicy.canManage(connection, user("admin", "admin", null)));
    }

    @Test
    void assignsTeamScopeFromTheCurrentOwnerAndClearsStaleTeamsOtherwise() {
        PuppetDatabaseConnection existing = connection("private");
        existing.setTeamId("old-team");
        User ownerInNewTeam = user("owner", "normal", "new-team");

        DatabaseConnectionPermissionPolicy.ScopeAssignment team =
                DatabaseConnectionPermissionPolicy.resolveScopeAssignment(
                        existing, ownerInNewTeam, "team", null);
        DatabaseConnectionPermissionPolicy.ScopeAssignment privateScope =
                DatabaseConnectionPermissionPolicy.resolveScopeAssignment(
                        existing, ownerInNewTeam, "private", null);

        assertEquals("team", team.scope());
        assertEquals("new-team", team.teamId());
        assertEquals("private", privateScope.scope());
        assertNull(privateScope.teamId());
        assertThrows(IllegalArgumentException.class,
                () -> DatabaseConnectionPermissionPolicy.resolveScopeAssignment(
                        existing, user("owner", "normal", null), "team", null));
    }

    @Test
    void letsAdminsSelectAnExplicitTeamWithoutReusingPrivateProfileMetadata() {
        PuppetDatabaseConnection existing = connection("private");
        existing.setTeamId("stale-team");

        DatabaseConnectionPermissionPolicy.ScopeAssignment assignment =
                DatabaseConnectionPermissionPolicy.resolveScopeAssignment(
                        existing, user("admin", "admin", "admin-team"), "team", "target-team");

        assertEquals("target-team", assignment.teamId());
        assertEquals(0, assignment.publicFlag());
    }

    private static PuppetDatabaseConnection connection(String scope) {
        PuppetDatabaseConnection connection = new PuppetDatabaseConnection();
        connection.setCreateUserId("owner");
        connection.setTeamId("team-a");
        connection.setScope(scope);
        return connection;
    }

    private static User user(String id, String privilege, String teamId) {
        User user = new User();
        user.setUserId(id);
        user.setPrivilege(privilege);
        user.setTeamId(teamId);
        return user;
    }
}
