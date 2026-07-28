package org.leo.jmg.generation;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.Disguise;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellGenerationServiceTest {

    private final ShellGenerationService service = new ShellGenerationService();

    @Test
    void webShellCommandCentralizesDefaultsAndMetadata() throws Exception {
        ShellGenerationOutcome outcome = service.generateWebShell(
                WebShellGenerationCommand.builder(
                                requestDisguise(), responseDisguise(), " jsp ")
                        .obfuscationSteps(java.util.Collections.<String>emptyList())
                        .obfuscationSeed(301L)
                        .build());

        Map<String, Object> metadata = outcome.getMetadata();
        assertEquals("JSP", metadata.get("type"));
        assertEquals("http", metadata.get("protocol"));
        assertEquals("auto", metadata.get("targetJavaVersion"));
        assertEquals("javax", metadata.get("servletNamespace"));
        assertEquals("301", metadata.get("obfuscationSeed"));
        assertTrue(outcome.getContent().length() > 100);
        assertThrows(UnsupportedOperationException.class,
                () -> metadata.put("protocol", "changed"));
    }

    @Test
    void memoryShellCommandReturnsFinalNamesBytesAndSharedSummary() throws Exception {
        ShellGenerationOutcome outcome = service.generateMemoryShell(
                baseMemoryCommand()
                        .protocol("httpchunk")
                        .servletNamespace("jakarta")
                        .obfuscationSeed(302L)
                        .build());
        GenerationResult result = outcome.getGenerationResult();

        assertArrayEquals(result.getInjectorClassBytes(),
                Base64.getDecoder().decode(outcome.getContent()));
        assertEquals(result.getInjectorClassName(),
                outcome.getMetadata().get("injectorClassName"));
        assertEquals("httpchunk", outcome.getMetadata().get("protocol"));
        assertEquals("X-Test : secret", outcome.getMetadata().get("headerConfig"));
        assertFalse((Boolean) outcome.getMetadata().get("templateMutated"));
        assertEquals(1,
                ((List<?>) outcome.getMetadata().get("compatibilityWarnings")).size());
    }

    @Test
    void websocketCommandOwnsEndpointAndHeaderDefaults() throws Exception {
        ShellGenerationOutcome outcome = service.generateMemoryShell(
                MemoryShellGenerationCommand.builder(
                                requestDisguise(), responseDisguise())
                        .serverType("Tomcat")
                        .injectorName("WebSocketInjector")
                        .packerType("DefaultBase64")
                        .protocol("websocket")
                        .obfuscationSeed(303L)
                        .build());

        assertEquals("/leo", outcome.getMetadata().get("urlPattern"));
        assertEquals("WebSocket endpoint: /leo",
                outcome.getMetadata().get("headerConfig"));
    }

    @Test
    void commandSnapshotsObfuscationSteps() throws Exception {
        List<String> steps =
                new ArrayList<String>(Arrays.asList("CHUNK_PAYLOAD"));
        MemoryShellGenerationCommand command = baseMemoryCommand()
                .obfuscationSteps(steps)
                .obfuscationSeed(304L)
                .build();
        steps.clear();

        ShellGenerationOutcome outcome = service.generateMemoryShell(command);
        assertTrue(outcome.getContent().length() > 20);
    }

    private static MemoryShellGenerationCommand.Builder baseMemoryCommand() {
        return MemoryShellGenerationCommand.builder(
                        requestDisguise(), responseDisguise())
                .header("X-Test", "secret")
                .serverType("Tomcat")
                .injectorName("FilterInjector")
                .packerType("DefaultBase64");
    }

    private static Disguise requestDisguise() {
        Disguise disguise = new Disguise();
        disguise.setDecodeBody(
                "public java.util.HashMap decode(byte[] data){return new java.util.HashMap();}");
        return disguise;
    }

    private static Disguise responseDisguise() {
        Disguise disguise = new Disguise();
        disguise.setEncodeBody(
                "public byte[] encode(java.util.HashMap data){return new byte[0];}");
        return disguise;
    }
}
