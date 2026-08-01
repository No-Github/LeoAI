package org.leo.ai.tools.platform;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leo.ai.agent.AiToolContext;
import org.leo.core.entity.AiExecutionPolicy;
import org.leo.core.entity.Puppet;
import org.leo.core.entity.User;
import org.leo.service.user.UserService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlatformToolAccessServiceTest {

    @AfterEach
    void clearContext() {
        AiToolContext.clear();
    }

    @Test
    void filtersPuppetsByOwnerTeamAndPublicScope() {
        UserService users = mock(UserService.class);
        User caller = user("caller", "normal", "team-a");
        when(users.getUserById("caller")).thenReturn(caller);
        PlatformToolAccessService access = new PlatformToolAccessService(users);
        AiToolContext.setExecutionPolicy(AiExecutionPolicy.from(caller));

        List<Puppet> visible = access.filterVisible(List.of(
                puppet("owned", "caller", null, "private"),
                puppet("team", "other", "team-a", "team"),
                puppet("public", "other", null, "public"),
                puppet("private", "other", null, "private")));

        assertEquals(List.of("owned", "team", "public"),
                visible.stream().map(Puppet::getPuppetId).toList());
    }

    @Test
    void onlyLeaderCanModifyTeamPuppetOwnedByAnotherUser() {
        UserService users = mock(UserService.class);
        PlatformToolAccessService access = new PlatformToolAccessService(users);
        Puppet teamPuppet = puppet("team", "other", "team-a", "team");
        User normal = user("normal", "normal", "team-a");
        User leader = user("leader", "leader", "team-a");

        assertFalse(access.canModify(teamPuppet, normal));
        assertTrue(access.canModify(teamPuppet, leader));
    }

    private static Puppet puppet(String id, String owner,
                                 String teamId, String permission) {
        Puppet puppet = new Puppet();
        puppet.setPuppetId(id);
        puppet.setCreateByUserId(owner);
        puppet.setTeamId(teamId);
        puppet.setPermission(permission);
        return puppet;
    }

    private static User user(String id, String privilege, String teamId) {
        User user = new User();
        user.setUserId(id);
        user.setUserName(id);
        user.setPrivilege(privilege);
        user.setTeamId(teamId);
        user.setStatus(1);
        return user;
    }
}
