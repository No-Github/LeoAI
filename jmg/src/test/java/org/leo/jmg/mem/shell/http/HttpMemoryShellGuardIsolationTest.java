package org.leo.jmg.mem.shell.http;

import org.apache.catalina.Valve;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.leo.jmg.mem.injectortpl.tomcat.TomcatProxyValveInjector;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HttpMemoryShellGuardIsolationTest {

    private static final String HEADER_NAME = "X-Guard";
    private static final String HEADER_VALUE = "secret";
    private static final int RESPONSE_CODE = 405;

    @BeforeEach
    void configureTemplates() throws Exception {
        configure(LeoValveTpl.class);
        configure(LeoValveChunkTpl.class);
        configure(LeoFilterTpl.class);
        configure(LeoFilterChunkTpl.class);
        configure(LeoServletTpl.class);
        configure(LeoServletChunkTpl.class);
    }

    @Test
    void valveTemplatesLeaveOrdinaryResponsesUntouched() throws Exception {
        assertValveMissLeavesResponseUntouched(new LeoValveTpl());
        assertValveMissLeavesResponseUntouched(new LeoValveChunkTpl());
    }

    @Test
    void filterTemplatesLeaveOrdinaryResponsesUntouched() throws Exception {
        assertFilterMissLeavesResponseUntouched(new LeoFilterTpl());
        assertFilterMissLeavesResponseUntouched(new LeoFilterChunkTpl());
    }

    @Test
    void configuredStatusIsAppliedOnlyAfterValveGuardMatches() throws Exception {
        assertValveHitSetsConfiguredStatus(new LeoValveTpl());
        assertValveHitSetsConfiguredStatus(new LeoValveChunkTpl());
    }

    @Test
    void configuredStatusIsAppliedOnlyAfterFilterGuardMatches() throws Exception {
        assertFilterHitSetsConfiguredStatus(new LeoFilterTpl());
        assertFilterHitSetsConfiguredStatus(new LeoFilterChunkTpl());
    }

    @Test
    void downstreamValveFailureIsNotExecutedTwice() throws Exception {
        Request request = mock(Request.class);
        Response response = mock(Response.class);
        Valve downstream = mock(Valve.class);
        LeoValveTpl shell = new LeoValveTpl();
        shell.setNext(downstream);
        when(request.getHeader(HEADER_NAME)).thenReturn(null);
        doThrow(new ServletException("business failure"))
                .when(downstream).invoke(request, response);

        assertThrows(ServletException.class, () -> shell.invoke(request, response));
        verify(downstream, times(1)).invoke(request, response);
        verifyNoInteractions(response);
    }

    @Test
    void downstreamFilterFailureIsNotExecutedTwice() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain downstream = mock(FilterChain.class);
        when(request.getHeader(HEADER_NAME)).thenReturn(null);
        doThrow(new ServletException("business failure"))
                .when(downstream).doFilter(request, response);

        assertThrows(ServletException.class,
                () -> new LeoFilterTpl().doFilter(request, response, downstream));
        verify(downstream, times(1)).doFilter(request, response);
        verifyNoInteractions(response);
    }

    @Test
    void proxyValveUsesShellChainInsteadOfCallingBusinessValveAgain() throws Throwable {
        Request request = mock(Request.class);
        Response response = mock(Response.class);
        Valve downstream = mock(Valve.class);
        LeoValveTpl shell = new LeoValveTpl();
        shell.setNext(downstream);
        when(request.getHeader(HEADER_NAME)).thenReturn(null);

        TomcatProxyValveInjector proxy =
                new TomcatProxyValveInjector(downstream, shell);
        Method invoke = Valve.class.getMethod(
                "invoke", Request.class, Response.class);
        proxy.invoke(null, invoke, new Object[]{request, response});

        verify(downstream, times(1)).invoke(request, response);
        verifyNoInteractions(response);
    }

    @Test
    void proxyValvePropagatesBusinessFailureWithoutRetry() throws Exception {
        Request request = mock(Request.class);
        Response response = mock(Response.class);
        Valve downstream = mock(Valve.class);
        LeoValveTpl shell = new LeoValveTpl();
        shell.setNext(downstream);
        when(request.getHeader(HEADER_NAME)).thenReturn(null);
        doThrow(new ServletException("business failure"))
                .when(downstream).invoke(request, response);

        TomcatProxyValveInjector proxy =
                new TomcatProxyValveInjector(downstream, shell);
        Method invoke = Valve.class.getMethod(
                "invoke", Request.class, Response.class);

        assertThrows(ServletException.class,
                () -> proxy.invoke(null, invoke, new Object[]{request, response}));
        verify(downstream, times(1)).invoke(request, response);
        verifyNoInteractions(response);
    }

    @Test
    void servletTemplatesDoNotApplyConfiguredStatusBeforeGuard() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getHeader(HEADER_NAME)).thenReturn(null);

        new LeoServletTpl().service(request, response);
        new LeoServletChunkTpl().service(request, response);

        verify(response, never()).setStatus(anyInt());
        verify(response, times(2)).sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    private static void assertValveMissLeavesResponseUntouched(Valve shell)
            throws Exception {
        Request request = mock(Request.class);
        Response response = mock(Response.class);
        Valve downstream = mock(Valve.class);
        shell.setNext(downstream);
        when(request.getHeader(HEADER_NAME)).thenReturn("ordinary");

        shell.invoke(request, response);

        verify(downstream, times(1)).invoke(request, response);
        verifyNoInteractions(response);
    }

    private static void assertFilterMissLeavesResponseUntouched(Filter shell)
            throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain downstream = mock(FilterChain.class);
        when(request.getHeader(HEADER_NAME)).thenReturn("ordinary");

        shell.doFilter(request, response, downstream);

        verify(downstream, times(1)).doFilter(request, response);
        verifyNoInteractions(response);
    }

    private static void assertValveHitSetsConfiguredStatus(Valve shell)
            throws Exception {
        Request request = mock(Request.class);
        Response response = mock(Response.class);
        Valve downstream = mock(Valve.class);
        shell.setNext(downstream);
        when(request.getHeader(HEADER_NAME)).thenReturn("prefix-secret-suffix");

        shell.invoke(request, response);

        verify(response).setStatus(RESPONSE_CODE);
        verify(downstream, never()).invoke(request, response);
    }

    private static void assertFilterHitSetsConfiguredStatus(Filter shell)
            throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain downstream = mock(FilterChain.class);
        when(request.getHeader(HEADER_NAME)).thenReturn("prefix-secret-suffix");

        shell.doFilter(request, response, downstream);

        verify(response).setStatus(RESPONSE_CODE);
        verify(downstream, never()).doFilter(request, response);
    }

    private static void configure(Class<?> template) throws Exception {
        setStaticField(template, "headerName", HEADER_NAME);
        setStaticField(template, "headerValue", HEADER_VALUE);
        setStaticField(template, "respCode", Integer.valueOf(RESPONSE_CODE));
    }

    private static void setStaticField(Class<?> type, String name, Object value)
            throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }
}
