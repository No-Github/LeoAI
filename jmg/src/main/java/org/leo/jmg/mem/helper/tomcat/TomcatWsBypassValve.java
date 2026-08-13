package org.leo.jmg.mem.helper.tomcat;

import org.apache.catalina.Valve;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;

import javax.servlet.ServletException;
import javax.websocket.server.ServerContainer;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** WebSocket 反向代理兼容 Valve 模板。 */
public class TomcatWsBypassValve implements Valve {
    public static String headerName;
    public static String headerValue;
    private Valve next;

    @Override
    public void invoke(Request request, Response response)
            throws IOException, ServletException {
        try {
            String header = request.getHeader(headerName);
            if (header != null && header.contains(headerValue)) {
                String pathInfo = request.getPathInfo();
                String path = pathInfo == null ? request.getServletPath()
                        : request.getServletPath() + pathInfo;
                Object container = request.getServletContext()
                        .getAttribute(ServerContainer.class.getName());
                if (container == null) {
                    throw new ServletException("Server container not found");
                }
                Object mapping = container.getClass()
                        .getMethod("findMapping", String.class)
                        .invoke(container, path);
                Class upgrade = Class.forName(
                        "org.apache.tomcat.websocket.server.UpgradeUtil");
                for (Method method : upgrade.getMethods()) {
                    if ("doUpgrade".equals(method.getName())) {
                        addHeader(request, "Connection", "upgrade");
                        addHeader(request, "Upgrade", "websocket");
                        method.invoke(null, container, request, response,
                                getFieldValue(mapping, "config"),
                                getFieldValue(mapping, "pathParams"));
                    }
                }
                return;
            }
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }
        getNext().invoke(request, response);
    }

    private Object getFieldValue(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private void addHeader(Request request, String key, String value) {
        try {
            Field field = request.getClass().getDeclaredField("coyoteRequest");
            field.setAccessible(true);
            Object coyoteRequest = field.get(request);
            Object headers = coyoteRequest.getClass().getMethod("getMimeHeaders")
                    .invoke(coyoteRequest);
            Object message = headers.getClass().getMethod("addValue", String.class)
                    .invoke(headers, key);
            message.getClass().getMethod("setString", String.class)
                    .invoke(message, value);
        } catch (Exception exception) {
            exception.printStackTrace();
        }
    }

    @Override
    public Valve getNext() {
        return next;
    }

    @Override
    public void setNext(Valve valve) {
        next = valve;
    }

    @Override
    public boolean isAsyncSupported() {
        return false;
    }

    @Override
    public void backgroundProcess() {
    }
}
