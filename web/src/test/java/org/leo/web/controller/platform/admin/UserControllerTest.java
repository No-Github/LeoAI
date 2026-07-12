package org.leo.web.controller.platform.admin;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.User;
import org.leo.service.team.TeamService;
import org.leo.service.user.UserService;
import org.leo.web.security.PasswordPolicy;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserControllerTest {

    @Test
    @SuppressWarnings("unchecked")
    void userListDoesNotMutateOrExposePasswordHash() {
        UserService userService = mock(UserService.class);
        User stored = new User();
        stored.setUserId("user-1");
        stored.setUserName("alice");
        stored.setPassword("pbkdf2-secret-hash");
        stored.setPrivilege(UserService.PRIVILEGE_NORMAL);
        stored.setStatus(1);
        when(userService.getAllUser()).thenReturn(List.of(stored));
        UserController controller = new UserController(
                userService, mock(TeamService.class), mock(PasswordPolicy.class));
        MockHttpServletRequest request = new MockHttpServletRequest();
        User admin = new User();
        admin.setPrivilege(UserService.PRIVILEGE_ADMIN);
        request.getSession(true).setAttribute("user", admin);

        Map<String, Object> response = controller.getUsers(request);
        List<Map<String, Object>> users = (List<Map<String, Object>>) response.get("data");

        assertEquals("pbkdf2-secret-hash", stored.getPassword());
        assertEquals("alice", users.get(0).get("userName"));
        assertFalse(users.get(0).containsKey("password"));
    }
}
