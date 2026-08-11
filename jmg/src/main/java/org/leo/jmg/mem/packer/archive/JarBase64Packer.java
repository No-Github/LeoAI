package org.leo.jmg.mem.packer.archive;

import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.Packer;
import org.leo.jmg.mem.packer.PackerMeta;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** 将 Core、Shell、Injector 以确定性条目顺序封装为 Base64 JAR。 */
@PackerMeta(name = "JarBase64", group = "Archive", order = 1)
public class JarBase64Packer implements Packer {

    @Override
    public String pack(ClassPackerConfig config) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        JarOutputStream jar = new JarOutputStream(output);
        try {
            Map<String, byte[]> entries = config.getClassEntries();
            if (entries.isEmpty()) {
                addEntry(jar, config.getClassName().replace('.', '/') + ".class",
                        config.getClassBytes());
            } else {
                for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                    addEntry(jar, entry.getKey(), entry.getValue());
                }
            }
        } finally {
            jar.close();
        }
        return Base64.getEncoder().encodeToString(output.toByteArray());
    }

    private static void addEntry(JarOutputStream jar,
                                 String name,
                                 byte[] bytes) throws Exception {
        JarEntry entry = new JarEntry(name);
        entry.setTime(0L);
        jar.putNextEntry(entry);
        jar.write(bytes);
        jar.closeEntry();
    }
}
