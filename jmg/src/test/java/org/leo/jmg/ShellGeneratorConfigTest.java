package org.leo.jmg;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.Disguise;
import org.leo.jmg.mem.packer.ClassPackerConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellGeneratorConfigTest {

    @Test
    void acceptsTrimmedCaseInsensitiveProtocol() {
        ShellGeneratorConfig config = builder()
                .protocol("  HTTPCHUNK  ")
                .build();

        assertEquals("httpchunk", config.getProtocol());
    }

    @Test
    void normalizesAllSupportedTransportProtocolNames() {
        assertEquals("httpchunk", builder().protocol("httpChunked").build().getProtocol());
        assertEquals("httpchunk", builder().protocol("HTTP-CHUNK").build().getProtocol());
        assertEquals("websocket", builder().protocol(" WebSocket ").build().getProtocol());
        assertThrows(IllegalArgumentException.class, () -> builder().protocol("ftp"));
    }

    @Test
    void exposesProtocolCapabilityMatrix() {
        assertEquals(Arrays.asList("http", "httpchunk"),
                ShellGeneratorConfig.getSupportedWebShellProtocols());
        assertEquals(Arrays.asList("http", "httpchunk", "websocket"),
                ShellGeneratorConfig.getSupportedMemoryShellProtocols());
    }

    @Test
    void enforcesProtocolBoundariesForWebAndMemoryArtifacts() {
        ShellGeneratorConfig websocketWebShell = builder().protocol("websocket").build();
        IllegalArgumentException webShellError = assertThrows(IllegalArgumentException.class,
                () -> websocketWebShell.validateForWebShell("JSP"));
        assertTrue(webShellError.getMessage().contains("内存构建"));

        // httpchunk 现已支持内存马：使用 chunked 传输编码的 HTTP 变体，
        // Shell 模板通过 Servlet API 透明处理 chunked 编码。
        ShellGeneratorConfig chunkedMemory = injectorBuilder()
                .protocol("httpchunk")
                .shellType("FilterInjector-HTTPCHUNK")
                .build();
        assertDoesNotThrow(() -> chunkedMemory.validateForInjector());

        ShellGeneratorConfig wrongWebSocketInjector = injectorBuilder()
                .protocol("websocket")
                .build();
        assertThrows(IllegalArgumentException.class, wrongWebSocketInjector::validateForInjector);

        ShellGeneratorConfig websocket = injectorBuilder()
                .protocol("websocket")
                .shellType("WebSocketInjector")
                .urlPattern("/socket")
                .build();
        assertDoesNotThrow(websocket::validateForInjector);
    }

    @Test
    void chunkInjectorAutoUpgradesProtocolAndValidatesRespCode() {
        // 未显式传 protocol 时，FilterInjector-HTTPCHUNK 自动升级到 httpchunk
        ShellGeneratorConfig chunk = injectorBuilder()
                .shellType("FilterInjector-HTTPCHUNK")
                .build();
        chunk.validateForInjector();
        assertEquals("httpchunk", chunk.getProtocol());

        // 显式 httpchunk 也通过
        ShellGeneratorConfig explicit = injectorBuilder()
                .protocol("httpchunk")
                .shellType("FilterInjector-HTTPCHUNK")
                .build();
        assertDoesNotThrow(explicit::validateForInjector);

        // websocket 与 chunk 不兼容
        ShellGeneratorConfig ws = injectorBuilder()
                .protocol("websocket")
                .shellType("FilterInjector-HTTPCHUNK")
                .build();
        assertThrows(IllegalArgumentException.class, ws::validateForInjector);

        // respCode 204 不允许持续响应体
        ShellGeneratorConfig badResp = injectorBuilder()
                .protocol("httpchunk")
                .shellType("FilterInjector-HTTPCHUNK")
                .respCode(204)
                .build();
        assertThrows(IllegalArgumentException.class, badResp::validateForInjector);
    }

    @Test
    void infersWebSocketForLegacyInjectorRequestsAndValidatesEndpointPath() {
        ShellGeneratorConfig legacy = injectorBuilder()
                .shellType("WebSocketInjector")
                .urlPattern("/socket")
                .build();
        legacy.validateForInjector();
        assertEquals("websocket", legacy.getProtocol());

        ShellGeneratorConfig invalidPath = injectorBuilder()
                .protocol("websocket")
                .shellType("WebSocketInjector")
                .urlPattern("/*")
                .build();
        assertThrows(IllegalArgumentException.class, invalidPath::validateForInjector);
    }

    @Test
    void requiresHeaderGuardForHttpMemoryArtifacts() {
        ShellGeneratorConfig missingHeader = builder()
                .serverType("Tomcat")
                .shellType("FilterInjector")
                .packerType("DefaultBase64")
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                missingHeader::validateForInjector);
        assertTrue(error.getMessage().contains("headerName"));
    }

    @Test
    void rejectsInvalidHttpStatusCode() {
        assertThrows(IllegalArgumentException.class, () -> builder().respCode(99));
        assertThrows(IllegalArgumentException.class, () -> builder().respCode(600));
        assertEquals(599, builder().respCode(599).build().getRespCode());
    }

    @Test
    void snapshotsObfuscationStepsAtConfigurationBoundary() {
        List<String> source = new ArrayList<String>(Arrays.asList("CHUNK_PAYLOAD"));

        ShellGeneratorConfig config = builder().jspObfuscationSteps(source).build();
        source.add("SPLIT_STRING_LITERALS");

        assertEquals(Arrays.asList("CHUNK_PAYLOAD"), config.getJspObfuscationSteps());
        assertThrows(UnsupportedOperationException.class,
                () -> config.getJspObfuscationSteps().add("GHOST_BITS_ENCODE"));
    }

    @Test
    void classPackerConfigAlsoSnapshotsSteps() {
        List<String> source = new ArrayList<String>(Arrays.asList("CHUNK_PAYLOAD"));
        ClassPackerConfig config = new ClassPackerConfig();

        config.setJspObfuscationSteps(source);
        source.clear();

        assertEquals(Arrays.asList("CHUNK_PAYLOAD"), config.getJspObfuscationSteps());
        assertThrows(UnsupportedOperationException.class,
                () -> config.getJspObfuscationSteps().clear());
    }

    @Test
    void parsesExplicitTargetJavaVersionsAndDefaultsToAuto() {
        assertEquals(TargetJavaVersion.AUTO, builder().build().getTargetJavaVersion());
        assertEquals(TargetJavaVersion.JDK_6,
                builder().targetJavaVersion("1.6").build().getTargetJavaVersion());
        assertEquals(TargetJavaVersion.JDK_8,
                builder().targetJavaVersion("jdk_8").build().getTargetJavaVersion());
        assertEquals(TargetJavaVersion.JDK_9_PLUS,
                builder().targetJavaVersion("11").build().getTargetJavaVersion());
        assertEquals(TargetJavaVersion.JDK_17_PLUS,
                builder().targetJavaVersion("17+").build().getTargetJavaVersion());
        assertThrows(IllegalArgumentException.class,
                () -> builder().targetJavaVersion("5"));
    }

    @Test
    void parsesServletNamespacesAndKeepsLegacyDefault() {
        assertEquals(ServletNamespace.AUTO, builder().build().getServletNamespace());
        assertEquals(ServletNamespace.JAVAX, builder().build().getEffectiveServletNamespace());
        assertEquals(ServletNamespace.JAKARTA,
                builder().servletNamespace("jakarta").build().getEffectiveServletNamespace());
        assertThrows(IllegalArgumentException.class,
                () -> builder().servletNamespace("unsupported"));
        assertThrows(IllegalArgumentException.class,
                () -> builder().servletNamespace("jakarta").targetJavaVersion("7").build().validate());
        assertEquals(1, builder().servletNamespace("jakarta").build()
                .getCompatibilityWarnings().size());
    }

    @Test
    void fixedSeedReproducesGeneratedNames() {
        ShellGeneratorConfig first = builder().obfuscationSeed(42L).build();
        ShellGeneratorConfig second = builder().obfuscationSeed(42L).build();

        assertEquals(42L, first.getObfuscationSeed());
        assertEquals(first.getCoreClassName(), second.getCoreClassName());
        assertEquals(first.getMethodAction(), second.getMethodAction());
        assertEquals(first.getFieldParams(), second.getFieldParams());
    }

    private ShellGeneratorConfig.Builder builder() {
        return ShellGeneratorConfig.builder(new Disguise(), new Disguise());
    }

    private ShellGeneratorConfig.Builder injectorBuilder() {
        return builder()
                .header("X-Test", "secret")
                .serverType("Tomcat")
                .shellType("FilterInjector")
                .packerType("DefaultBase64")
                .urlPattern("/*");
    }
}
