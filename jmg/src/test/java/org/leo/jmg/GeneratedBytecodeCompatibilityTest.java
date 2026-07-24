package org.leo.jmg;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtNewMethod;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leo.core.entity.Disguise;
import org.leo.core.util.asm.ClassFileMinimizer;
import org.leo.jmg.core.LeoCore;
import org.leo.jmg.mem.injectortpl.InjectorGenerator;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedBytecodeCompatibilityTest {

    private static final int JAVA_5_CLASS_MAJOR = 49;

    @Test
    void coreShellAndInjectorStayLoadableByLegacyJvms() throws Exception {
        ShellGeneratorConfig config = createConfig();

        byte[] core = new LeoCore(config.getReqDisguise(), config.getRespDisguise())
                .genLeoCoreByClassName(config.getCoreClassName(), config);
        core = ClassFileMinimizer.transform(core);
        assertLegacyClassFile(core, "LeoCore");
        config.setCoreClassBytes(core);

        byte[] shell = new org.leo.jmg.mem.shell.ShellGenerator()
                .makeShell(config, "LeoFilterTpl");
        assertLegacyClassFile(shell, "Shell");
        config.setShellClassBytes(shell);

        byte[] injector = new InjectorGenerator().makeInjector(
                config,
                "org.leo.jmg.mem.injectortpl.tomcat.TomcatFilterInjector"
        );
        assertLegacyClassFile(injector, "Injector");
    }

    @Test
    void generatedCoreLoadsInConfiguredLegacyJvm(@TempDir Path tempDir) throws Exception {
        String javaExecutable = System.getProperty("jmg.legacy.java");
        Assumptions.assumeTrue(javaExecutable != null && !javaExecutable.trim().isEmpty(),
                "通过 -Djmg.legacy.java=/path/to/java 启用真实旧 JDK 验证");

        ShellGeneratorConfig config = createConfig();
        byte[] core = new LeoCore(config.getReqDisguise(), config.getRespDisguise())
                .genLeoCoreByClassName(config.getCoreClassName(), config);
        core = ClassFileMinimizer.transform(core);

        writeClass(tempDir, config.getCoreClassName(), core);
        writeClass(tempDir, "org.example.LegacyVerifier", createLegacyVerifier());

        Process process = new ProcessBuilder(
                javaExecutable,
                "-Xverify:all",
                "-cp",
                tempDir.toString(),
                "org.example.LegacyVerifier",
                config.getCoreClassName()
        ).redirectErrorStream(true).start();
        byte[] output = readAll(process.getInputStream());
        int exitCode = process.waitFor();

        assertEquals(0, exitCode,
                "旧 JDK 加载生成类失败: " + new String(output, StandardCharsets.UTF_8));
        assertEquals("OK", new String(output, StandardCharsets.UTF_8));
    }

    @Test
    void jakartaNamespaceRemapsShellAndInjectorTypeReferences() throws Exception {
        ShellGeneratorConfig config = createConfig(ServletNamespace.JAKARTA);
        byte[] core = new LeoCore(config.getReqDisguise(), config.getRespDisguise())
                .genLeoCoreByClassName(config.getCoreClassName(), config);
        config.setCoreClassBytes(ClassFileMinimizer.transform(core));

        byte[] shell = new org.leo.jmg.mem.shell.ShellGenerator()
                .makeShell(config, "LeoFilterTpl");
        String shellConstants = new String(shell, StandardCharsets.ISO_8859_1);
        assertFalse(shellConstants.contains("javax/servlet"));
        assertTrue(shellConstants.contains("jakarta/servlet"));
        Class<?> shellClass = new ByteArrayClassLoader().define(shell);
        assertTrue(Filter.class.isAssignableFrom(shellClass));

        config.setShellClassBytes(shell);
        byte[] injector = new InjectorGenerator().makeInjector(
                config,
                "org.leo.jmg.mem.injectortpl.undertow.UndertowFilterInjector"
        );
        String injectorConstants = new String(injector, StandardCharsets.ISO_8859_1);
        assertFalse(injectorConstants.contains("javax/servlet/DispatcherType"));
        assertTrue(injectorConstants.contains("jakarta/servlet/DispatcherType"));
    }

    @Test
    void jakartaNamespaceRemapsWebSocketEndpointReferences() throws Exception {
        ShellGeneratorConfig config = createConfig(ServletNamespace.JAKARTA);
        config.setCoreClassBytes(new byte[]{1, 2, 3});
        ShellGeneratorConfig websocketConfig = ShellGeneratorConfig
                .builder(config.getReqDisguise(), config.getRespDisguise())
                .coreClassName(config.getCoreClassName())
                .shellClassName("org.example.JakartaWebSocket")
                .injectorClassName("org.example.JakartaWebSocketInjector")
                .serverType("Tomcat")
                .shellType("WebSocketInjector")
                .packerType("DefaultBase64")
                .protocol("websocket")
                .urlPattern("/socket")
                .servletNamespace(ServletNamespace.JAKARTA)
                .build();
        websocketConfig.setCoreClassBytes(config.getCoreClassBytes());

        byte[] shell = new org.leo.jmg.mem.shell.ShellGenerator()
                .makeShell(websocketConfig, "LeoWebSocketTpl");
        String constants = new String(shell, StandardCharsets.ISO_8859_1);
        assertFalse(constants.contains("javax/websocket"));
        assertTrue(constants.contains("jakarta/websocket"));
    }

    private static void assertLegacyClassFile(byte[] classBytes, String label) {
        assertEquals(JAVA_5_CLASS_MAJOR, majorVersion(classBytes),
                label + " 字节码版本必须兼容 JDK 6/7/8");
        assertFalse(new String(classBytes, StandardCharsets.ISO_8859_1)
                        .contains("java/util/Base64"),
                label + " 不应直接链接仅 JDK 8 提供的 Base64 API");
    }

    private static int majorVersion(byte[] classBytes) {
        return ((classBytes[6] & 0xff) << 8) | (classBytes[7] & 0xff);
    }

    private static byte[] createLegacyVerifier() throws Exception {
        ClassPool pool = new ClassPool(true);
        CtClass verifier = pool.makeClass("org.example.LegacyVerifier");
        verifier.getClassFile().setVersionToJava5();
        verifier.addMethod(CtNewMethod.make(
                "public static void main(String[] args) throws Exception {"
                        + "Class.forName(args[0],true,Thread.currentThread().getContextClassLoader());"
                        + "System.out.print(\"OK\");}",
                verifier
        ));
        try {
            return ClassFileMinimizer.transform(verifier.toBytecode());
        } finally {
            verifier.detach();
        }
    }

    private static void writeClass(Path root, String className, byte[] bytes) throws Exception {
        Path target = root.resolve(className.replace('.', '/') + ".class");
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }

    private static byte[] readAll(java.io.InputStream input) throws Exception {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int length;
        while ((length = input.read(buffer)) != -1) {
            output.write(buffer, 0, length);
        }
        return output.toByteArray();
    }

    private static ShellGeneratorConfig createConfig() {
        return createConfig(ServletNamespace.AUTO);
    }

    private static ShellGeneratorConfig createConfig(ServletNamespace servletNamespace) {
        Disguise request = new Disguise();
        request.setDecodeBody(
                "public java.util.HashMap decode(byte[] data){return new java.util.HashMap();}"
        );

        Disguise response = new Disguise();
        response.setEncodeBody(
                "public byte[] encode(java.util.HashMap data){return new byte[0];}"
        );

        return ShellGeneratorConfig.builder(request, response)
                .coreClassName("org.example.LegacyCore")
                .shellClassName("org.example.LegacyFilter")
                .injectorClassName("org.example.LegacyInjector")
                .header("X-Test", "legacy")
                .serverType("Tomcat")
                .shellType("FilterInjector")
                .packerType("DefaultBase64")
                .servletNamespace(servletNamespace)
                // Regression seed: fieldResults used to become "buffer" and
                // collide with a fixed redirect local in Javassist source.
                .obfuscationSeed(-2840755419257969001L)
                .build();
    }

    private static final class ByteArrayClassLoader extends ClassLoader {
        private Class<?> define(byte[] bytes) {
            return defineClass(null, bytes, 0, bytes.length);
        }
    }
}
