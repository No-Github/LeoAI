package org.leo.web.security;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.User;
import org.leo.service.PuppetService;
import org.leo.service.user.UserService;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PermissionServiceTest {

    @Test
    void refreshesActiveUserFromDatabase() {
        UserService users = mock(UserService.class);
        PermissionService service = new PermissionService(mock(PuppetService.class), users);
        MockHttpServletRequest request = requestWithUser("user-1");
        User fresh = new User();
        fresh.setUserId("user-1");
        fresh.setStatus(1);
        when(users.getUserById("user-1")).thenReturn(fresh);

        assertSame(fresh, service.getCurrentUser(request));
        assertSame(fresh, request.getSession(false).getAttribute("user"));
    }

    @Test
    void invalidatesSessionWhenAccountIsDisabled() {
        UserService users = mock(UserService.class);
        PermissionService service = new PermissionService(mock(PuppetService.class), users);
        MockHttpServletRequest request = requestWithUser("user-1");
        User disabled = new User();
        disabled.setUserId("user-1");
        disabled.setStatus(0);
        when(users.getUserById("user-1")).thenReturn(disabled);
        var session = (org.springframework.mock.web.MockHttpSession) request.getSession(false);

        assertNull(service.getCurrentUser(request));
        assertTrue(session.isInvalid());
    }

    private static MockHttpServletRequest requestWithUser(String userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        User cached = new User();
        cached.setUserId(userId);
        cached.setStatus(1);
        request.getSession(true).setAttribute("user", cached);
        return request;
    }
}
