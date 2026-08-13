package org.leo.jmg.mem.packer.archive;

import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.Packer;
import org.leo.jmg.mem.packer.PackerMeta;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.JarURLConnection;
import java.net.URLConnection;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/** 生成同时支持 -javaagent 与 Attach API loadAgent 的 Base64 Agent JAR。 */
@PackerMeta(name = "AgentJarBase64", group = "Archive", order = 2,
        minTargetJava = 8, supportedProtocols = {"http", "httpchunk"})
public class AgentJarBase64Packer implements Packer {

    @Override
    public String pack(ClassPackerConfig config) throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.putValue("Manifest-Version", "1.0");
        attributes.putValue("Agent-Class", config.getClassName());
        attributes.putValue("Premain-Class", config.getClassName());
        attributes.putValue("Can-Redefine-Classes", "true");
        attributes.putValue("Can-Retransform-Classes", "true");

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        JarOutputStream jar = new JarOutputStream(output, manifest);
        Set<String> names = new HashSet<String>();
        names.add("META-INF/MANIFEST.MF");
        try {
            Map<String, byte[]> entries = config.getClassEntries();
            if (entries.isEmpty()) {
                addEntry(jar, names, config.getClassName().replace('.', '/') + ".class",
                        config.getClassBytes());
            } else {
                for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                    addEntry(jar, names, entry.getKey(), entry.getValue());
                }
            }
            addPackageFromCodeSource(jar, names, ClassReader.class, "org/objectweb/asm/");
            addPackageFromCodeSource(jar, names, ClassNode.class, "org/objectweb/asm/tree/");
        } finally {
            jar.close();
        }
        return Base64.getEncoder().encodeToString(output.toByteArray());
    }

    private static void addPackageFromCodeSource(JarOutputStream output,
                                                  Set<String> names,
                                                  Class anchor,
                                                  String packagePrefix) throws Exception {
        try {
            URL location = anchor.getProtectionDomain().getCodeSource().getLocation();
            URI uri = location.toURI();
            File source = new File(uri);
            if (source.isFile()) {
                JarFile dependency = new JarFile(source);
                try {
                    addEntriesFromJar(output, names, dependency, packagePrefix);
                } finally {
                    dependency.close();
                }
                return;
            }
            File root = new File(source, packagePrefix);
            if (root.isDirectory()) {
                addDirectory(output, names, source, root);
                return;
            }
        } catch (Throwable ignored) {
            // Spring Boot nested-jar 等非 file CodeSource 继续走类资源连接。
        }

        String anchorResource = anchor.getName().replace('.', '/') + ".class";
        URL resource = anchor.getClassLoader().getResource(anchorResource);
        URLConnection connection = resource == null ? null : resource.openConnection();
        if (connection instanceof JarURLConnection) {
            JarFile dependency = ((JarURLConnection) connection).getJarFile();
            addEntriesFromJar(output, names, dependency, packagePrefix);
            return;
        }
        throw new IllegalStateException("未找到可枚举的 ASM 运行时资源: " + anchorResource);
    }

    private static void addEntriesFromJar(JarOutputStream output,
                                          Set<String> names,
                                          JarFile dependency,
                                          String packagePrefix) throws Exception {
        java.util.Enumeration<JarEntry> entries = dependency.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String name = entry.getName();
            if (!entry.isDirectory() && name.startsWith(packagePrefix)
                    && name.endsWith(".class")) {
                InputStream input = dependency.getInputStream(entry);
                try {
                    addEntry(output, names, name, readAll(input));
                } finally {
                    input.close();
                }
            }
        }
    }

    private static void addDirectory(JarOutputStream output, Set<String> names,
                                     File sourceRoot, File current) throws Exception {
        File[] files = current.listFiles();
        if (files == null) return;
        for (int i = 0; i < files.length; i++) {
            File file = files[i];
            if (file.isDirectory()) {
                addDirectory(output, names, sourceRoot, file);
            } else if (file.getName().endsWith(".class")) {
                String name = sourceRoot.toURI().relativize(file.toURI()).getPath();
                InputStream input = new FileInputStream(file);
                try {
                    addEntry(output, names, name, readAll(input));
                } finally {
                    input.close();
                }
            }
        }
    }

    private static byte[] readAll(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] block = new byte[8192];
        int read;
        while ((read = input.read(block)) != -1) output.write(block, 0, read);
        return output.toByteArray();
    }

    private static void addEntry(JarOutputStream jar, Set<String> names,
                                 String name, byte[] bytes) throws Exception {
        if (bytes == null || !names.add(name)) return;
        JarEntry entry = new JarEntry(name);
        entry.setTime(0L);
        jar.putNextEntry(entry);
        jar.write(bytes);
        jar.closeEntry();
    }
}
