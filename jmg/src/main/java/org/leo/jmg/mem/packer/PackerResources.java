package org.leo.jmg.mem.packer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Objects;

/**
 * 负责读取 Packer 内置模板资源。
 */
public final class PackerResources {

    private PackerResources() {
    }

    public static String loadTemplate(String resourceName) {
        try (InputStream stream = Objects.requireNonNull(
                PackerResources.class.getResourceAsStream(resourceName),
                "Packer 模板资源不存在: " + resourceName)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int length;
            while ((length = stream.read(chunk)) != -1) {
                buffer.write(chunk, 0, length);
            }
            return buffer.toString(Charset.defaultCharset().name());
        } catch (IOException e) {
            throw new IllegalStateException("读取 Packer 模板失败: " + resourceName, e);
        }
    }
}
