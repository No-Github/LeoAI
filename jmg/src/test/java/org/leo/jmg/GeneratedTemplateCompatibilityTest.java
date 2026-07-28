package org.leo.jmg;

import org.junit.jupiter.api.Test;
import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.PackerResources;
import org.leo.jmg.mem.packer.h2.H2JavacPacker;
import org.leo.jmg.mem.packer.obfuscation.PayloadObfuscator;
import org.leo.jmg.mem.packer.scriptengine.DefaultScriptEnginePacker;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedTemplateCompatibilityTest {

    @Test
    void jspTemplatesAvoidJava8ReflectionConvenienceMethods() {
        assertNotContains("/memshell-template/shell1.jsp.txt", ".getParameterCount(");
        assertNotContains("/memshell-template/shell2.jsp.txt", ".getParameterCount(");
    }

    @Test
    void scriptEngineBypassAvoidsJava9ReadAllBytes() {
        assertNotContains("/memshell-template/ScriptEngineBypassModule.js.txt", ".readAllBytes(");

        ClassPackerConfig config = createConfig(true);
        String output = new DefaultScriptEnginePacker().pack(config);
        assertFalse(output.contains(".readAllBytes("));
        assertFalse(output.contains("{{"), "渲染后不应残留模板占位符");
    }

    @Test
    void groovyTemplateDoesNotDirectlyLinkJava8Base64Api() {
        assertNotContains("/memshell-template/shell.groovy.txt", "java.util.Base64.getDecoder(");
        assertNotContains("/memshell-template/shell.groovy.txt", "java.util.Base64.Decoder");
    }

    @Test
    void h2GeneratedSourceUsesReflectiveBase64Fallbacks() {
        String output = new H2JavacPacker().pack(createConfig(false));
        assertFalse(output.contains("java.util.Base64.getDecoder("));
        assertTrue(output.contains("Class.forName(\"java.util.Base64\")"));
        assertTrue(output.contains("javax.xml.bind.DatatypeConverter"));
        assertTrue(output.contains("sun.misc.BASE64Decoder"));
    }

    @Test
    void xorRuntimeHelperAvoidsDirectJava8Base64Linkage() {
        String payload = repeat('A', 120);
        String output = PayloadObfuscator.xor(
                "<%! %><% String payload=\"" + payload + "\"; %>");

        assertFalse(output.contains("java.util.Base64.getDecoder("));
        assertFalse(output.contains("java.util.Base64.getEncoder("));
        assertTrue(output.contains("javax.xml.bind.DatatypeConverter"));
    }

    private static void assertNotContains(String resource, String forbidden) {
        String content = PackerResources.loadTemplate(resource);
        assertFalse(content.contains(forbidden), resource + " 不应包含 " + forbidden);
    }

    private static ClassPackerConfig createConfig(boolean bypassJavaModule) {
        byte[] bytes = new byte[]{0, 1, 2, 3};
        ClassPackerConfig config = new ClassPackerConfig();
        config.setClassName("org.example.GeneratedPayload");
        config.setClassBytes(bytes);
        config.setClassBytesBase64Str(Base64.getEncoder().encodeToString(bytes));
        config.setByPassJavaModule(bypassJavaModule);
        return config;
    }

    private static String repeat(char value, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }
}
