package org.leo.web.security;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.Puppet;
import org.leo.core.entity.User;
import org.leo.core.session.PuppetNodeSession;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionPolicyTest {

    @Test
    void isolatesPuppetSessionsByOwnerUnlessCallerIsAdmin() {
        PuppetNodeSession session = new PuppetNodeSession();
        session.setCreateByUser("owner");

        assertTrue(PermissionPolicy.canAccessSession(session, user("owner", "normal", "team-a")));
        assertFalse(PermissionPolicy.canAccessSession(session, user("other", "leader", "team-a")));
        assertTrue(PermissionPolicy.canAccessSession(session, user("admin", "admin", null)));
    }

    @Test
    void appliesPrivateTeamAndPublicPuppetVisibility() {
        Puppet puppet = new Puppet();
        puppet.setCreateByUserId("owner");
        puppet.setTeamId("team-a");
        puppet.setPermission("private");

        assertTrue(PermissionPolicy.canAccessPuppet(puppet, user("owner", "normal", "team-a")));
        assertFalse(PermissionPolicy.canAccessPuppet(puppet, user("other", "normal", "team-a")));

        puppet.setPermission("team");
        assertTrue(PermissionPolicy.canAccessPuppet(puppet, user("other", "normal", "team-a")));
        assertFalse(PermissionPolicy.canAccessPuppet(puppet, user("outsider", "normal", "team-b")));

        puppet.setPermission("public");
        assertTrue(PermissionPolicy.canAccessPuppet(puppet, user("outsider", "normal", "team-b")));
    }

    private static User user(String id, String privilege, String teamId) {
        User user = new User();
        user.setUserId(id);
        user.setPrivilege(privilege);
        user.setTeamId(teamId);
        return user;
    }
}
