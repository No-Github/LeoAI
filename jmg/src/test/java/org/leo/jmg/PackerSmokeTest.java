package org.leo.jmg;

import org.junit.jupiter.api.Test;
import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.Packer;
import org.leo.jmg.mem.packer.PackerRegistry;
import org.leo.jmg.mem.packer.spel.SpELSpringGzipJDK17Packer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PackerSmokeTest {

    @Test
    void everyRegisteredPackerProducesOutput() throws Exception {
        ClassPackerConfig config = createConfig();
        for (String name : PackerRegistry.getAllNames()) {
            Packer packer = PackerRegistry.getOrThrow(name);
            String output = packer.pack(config);
            assertFalse(output == null || output.trim().isEmpty(), name + " 返回了空结果");
        }
    }

    @Test
    void springJdk17PackerRejectsMalformedClassNamesClearly() {
        assertThrows(IllegalArgumentException.class,
                () -> SpELSpringGzipJDK17Packer.assertClassNameValid(null));
        assertThrows(UnsupportedOperationException.class,
                () -> SpELSpringGzipJDK17Packer.assertClassNameValid("NoPackage"));
        assertThrows(UnsupportedOperationException.class,
                () -> SpELSpringGzipJDK17Packer.assertClassNameValid("org.springframework.expression."));
    }

    private ClassPackerConfig createConfig() throws IOException {
        byte[] classBytes = readFixtureClassBytes();
        ClassPackerConfig config = new ClassPackerConfig();
        config.setClassName("org.springframework.expression.CommonUtil");
        config.setClassBytes(classBytes);
        config.setClassBytesBase64Str(Base64.getEncoder().encodeToString(classBytes));
        return config;
    }

    private byte[] readFixtureClassBytes() throws IOException {
        String resource = "/" + PackerSmokeTest.class.getName().replace('.', '/') + ".class";
        try (InputStream input = PackerSmokeTest.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("找不到测试类字节码: " + resource);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }
}
