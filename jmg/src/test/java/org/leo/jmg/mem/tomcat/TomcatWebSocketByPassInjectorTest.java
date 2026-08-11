package org.leo.jmg.mem.tomcat;

import org.apache.tomcat.websocket.server.UpgradeUtil;
import org.junit.jupiter.api.Test;
import org.leo.jmg.mem.injectortpl.tomcat.TomcatWebSocketByPassInjector;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class TomcatWebSocketByPassInjectorTest {

    @Test
    void proxyValveUpgradesMatchingQueryGateAndAddsHandshakeHeaders()
            throws Throwable {
        setStatic("headerName", "token");
        setStatic("headerValue", "secret");
        UpgradeUtil.lastArguments = null;

        Constructor<TomcatWebSocketByPassInjector> constructor =
                TomcatWebSocketByPassInjector.class
                        .getDeclaredConstructor(Object.class);
        constructor.setAccessible(true);
        TomcatWebSocketByPassInjector handler = constructor.newInstance((Object) null);
        Request request = new Request();
        Response response = new Response();
        Method invoke = Valve.class.getMethod("invoke", Request.class, Response.class);

        assertNull(handler.invoke(new Object(), invoke,
                new Object[]{request, response}));
        assertNotNull(UpgradeUtil.lastArguments);
        assertEquals("upgrade", request.coyoteRequest.headers.values.get("Connection"));
        assertEquals("websocket", request.coyoteRequest.headers.values.get("Upgrade"));
    }

    private static void setStatic(String name, Object value) throws Exception {
        Field field = TomcatWebSocketByPassInjector.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    public interface Valve {
        void invoke(Request request, Response response);
    }

    public static class Request {
        final CoyoteRequest coyoteRequest = new CoyoteRequest();
        final ServletContext servletContext = new ServletContext();

        public String getHeader(String name) {
            return null;
        }

        public String getParameter(String name) {
            return "secret";
        }

        public String getPathInfo() {
            return "/socket";
        }

        public String getServletPath() {
            return "";
        }

        public ServletContext getServletContext() {
            return servletContext;
        }
    }

    public static class Response {
    }

    public static class ServletContext {
        final Container container = new Container();

        public Object getAttribute(String name) {
            return name.endsWith("ServerContainer") ? container : null;
        }
    }

    public static class Container {
        final Mapping mapping = new Mapping();

        public Object findMapping(String path) {
            return "/socket".equals(path) ? mapping : null;
        }
    }

    public static class Mapping {
        private final Object config = "config";
        private final Object pathParams = "params";
    }

    public static class CoyoteRequest {
        final MimeHeaders headers = new MimeHeaders();

        public MimeHeaders getMimeHeaders() {
            return headers;
        }
    }

    public static class MimeHeaders {
        final Map<String, String> values = new HashMap<String, String>();

        public MessageBytes addValue(String key) {
            return new MessageBytes(values, key);
        }
    }

    public static class MessageBytes {
        private final Map<String, String> values;
        private final String key;

        MessageBytes(Map<String, String> values, String key) {
            this.values = values;
            this.key = key;
        }

        public void setString(String value) {
            values.put(key, value);
        }
    }
}
