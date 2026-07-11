package org.leo.web.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.leo.core.entity.User;
import org.leo.web.security.RoleAwareAdminEndpoint;
import org.springframework.web.method.HandlerMethod;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoginInterceptorTest {

    private final LoginInterceptor interceptor = new LoginInterceptor();

    @Test
    void rejectsNormalUserReadingAdminModelConfiguration() throws Exception {
        User user = new User();
        user.setPrivilege("normal");

        HttpServletRequest request = requestFor(user, "/platform/admin/ai-models");
        HttpServletResponse response = responseWithWriter();

        assertFalse(interceptor.preHandle(request, response, handlerFor(DefaultAdminHandler.class)));
    }

    @Test
    void rejectsNormalUserReadingAuditLogsByDefault() throws Exception {
        User user = normalUser();
        HttpServletRequest request = requestFor(user, "/platform/admin/audit-logs");

        assertFalse(interceptor.preHandle(request, responseWithWriter(), handlerFor(DefaultAdminHandler.class)));
    }

    @Test
    void allowsExplicitRoleAwareAdminControllerToApplyItsOwnPolicy() throws Exception {
        User user = normalUser();

        assertTrue(interceptor.preHandle(
                requestFor(user, "/platform/admin/users"),
                mock(HttpServletResponse.class),
                handlerFor(RoleAwareHandler.class)));
    }

    @Test
    void allowsNormalUserReadingSanitizedModelCatalog() {
        User user = normalUser();

        assertTrue(interceptor.preHandle(
                requestFor(user, "/platform/ai/models"),
                mock(HttpServletResponse.class),
                mock(HandlerMethod.class)));
    }

    private static User normalUser() {
        User user = new User();
        user.setPrivilege("normal");
        return user;
    }

    private static HandlerMethod handlerFor(Class<?> type) throws NoSuchMethodException {
        Method method = type.getDeclaredMethod("handle");
        return new HandlerMethod(newInstance(type), method);
    }

    private static Object newInstance(Class<?> type) {
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpServletRequest requestFor(User user, String uri) {
        HttpSession session = mock(HttpSession.class);
        when(session.getAttribute("user")).thenReturn(user);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getContextPath()).thenReturn("");
        when(request.getRequestURI()).thenReturn(uri);
        when(request.getSession()).thenReturn(session);
        return request;
    }

    private static HttpServletResponse responseWithWriter() throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        return response;
    }

    private static class DefaultAdminHandler {
        public void handle() {
        }
    }

    @RoleAwareAdminEndpoint
    private static class RoleAwareHandler {
        public void handle() {
        }
    }
}
