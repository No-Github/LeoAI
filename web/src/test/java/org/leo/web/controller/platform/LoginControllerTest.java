package org.leo.web.controller.platform;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.User;
import org.leo.core.util.PasswordUtil;
import org.leo.service.user.UserService;
import org.leo.web.dto.platform.user.LoginRequest;
import org.leo.web.exception.ApiException;
import org.leo.web.security.PermissionService;
import org.leo.web.security.LoginAttemptService;
import org.leo.web.security.PasswordPolicy;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

class LoginControllerTest {

    private final UserService userService = mock(UserService.class);
    private final PermissionService permissionService = mock(PermissionService.class);
    private final LoginAttemptService loginAttemptService = mock(LoginAttemptService.class);
    private final PasswordPolicy passwordPolicy = mock(PasswordPolicy.class);
    private final LoginController controller = new LoginController(
            userService, permissionService, loginAttemptService, passwordPolicy);

    @Test
    void upgradesLegacyPasswordAndRotatesSessionOnSuccessfulLogin() {
        User user = new User();
        user.setUserId("legacy-user");
        user.setUserName("legacy");
        user.setPassword(PasswordUtil.md5("secret"));
        user.setStatus(1);
        when(userService.getUserByName("legacy")).thenReturn(user);
        when(userService.recordSuccessfulLogin(eq("legacy-user"), anyString()))
                .thenAnswer(invocation -> {
                    user.setPassword(invocation.getArgument(1));
                    return user;
                });
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true);
        String previousSessionId = request.getSession().getId();

        var response = controller.login(request, new LoginRequest("legacy", "secret"));

        assertEquals(200, response.get("code"));
        assertFalse(PasswordUtil.needsRehash(user.getPassword()));
        assertSame(user, request.getSession(false).getAttribute("user"));
        verify(userService).recordSuccessfulLogin(eq("legacy-user"), anyString());
        verify(loginAttemptService).recordSuccess("legacy", "127.0.0.1");
        // MockHttpServletRequest supports session rotation just like the servlet container.
        assertFalse(previousSessionId.equals(request.getSession(false).getId()));
    }

    @Test
    void returnsUnauthorizedForInvalidCredentials() {
        when(userService.getUserByName("missing")).thenReturn(null);

        ApiException error = assertThrows(ApiException.class,
                () -> controller.login(new MockHttpServletRequest(),
                        new LoginRequest("missing", "wrong")));

        assertEquals(401, error.getCode());
        verify(loginAttemptService).recordFailure("missing", "127.0.0.1");
    }

    @Test
    void rejectsDisabledUserEvenWhenPasswordIsCorrect() {
        User user = new User();
        user.setUserId("disabled-user");
        user.setUserName("disabled");
        user.setPassword(PasswordUtil.hash("secret"));
        user.setStatus(0);
        when(userService.getUserByName("disabled")).thenReturn(user);

        ApiException error = assertThrows(ApiException.class,
                () -> controller.login(new MockHttpServletRequest(),
                        new LoginRequest("disabled", "secret")));

        assertEquals(403, error.getCode());
        verify(loginAttemptService).recordFailure("disabled", "127.0.0.1");
    }
}
