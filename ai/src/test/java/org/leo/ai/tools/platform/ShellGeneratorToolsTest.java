package org.leo.ai.tools.platform;

import org.junit.jupiter.api.Test;
import org.leo.ai.channel.DelegatingChatModel;
import org.leo.core.entity.Disguise;
import org.leo.core.entity.Puppet;
import org.leo.core.generator.GeneratedArtifact;
import org.leo.core.generator.GenerationRequest;
import org.leo.core.generator.ScriptGeneratorProvider;
import org.leo.core.runtime.PuppetRuntime;
import org.leo.service.PuppetService;
import org.leo.service.disguise.DisguiseService;
import org.leo.service.generator.ScriptGeneratorService;
import org.leo.service.shell.ShellResultStore;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShellGeneratorToolsTest {

    @Test
    void exposesPuppetRuntimeForGeneratorSelection() throws Exception {
        Puppet puppet = new Puppet();
        puppet.setPuppetId("php-node");
        puppet.setPuppetName("PHP Node");
        puppet.setType("PHP");
        puppet.setProtocol("http");
        PuppetService puppetService = mock(PuppetService.class);
        when(puppetService.findPuppetById("php-node")).thenReturn(puppet);
        ShellGeneratorTools tools = new ShellGeneratorTools(mock(DisguiseService.class),
                mock(DelegatingChatModel.class), new ShellResultStore(), puppetService,
                new ScriptGeneratorService(List.of(phpProvider(new AtomicReference<>()))));

        Map<String, Object> config = tools.getPuppetShellConfig("php-node");

        assertEquals("php", config.get("runtime"));
        assertEquals("http", config.get("protocol"));
        assertTrue(String.valueOf(config.get("tip")).contains("generatePhpWebShell"));
    }

    @Test
    void convertsPersistedChunkedProtocolToGeneratorProtocol() throws Exception {
        Puppet puppet = new Puppet();
        puppet.setPuppetId("chunk-node");
        puppet.setType("java");
        puppet.setProtocol("httpChunked");
        PuppetService puppetService = mock(PuppetService.class);
        when(puppetService.findPuppetById("chunk-node")).thenReturn(puppet);
        ShellGeneratorTools tools = new ShellGeneratorTools(mock(DisguiseService.class),
                mock(DelegatingChatModel.class), new ShellResultStore(), puppetService,
                new ScriptGeneratorService(List.of()));

        Map<String, Object> config = tools.getPuppetShellConfig("chunk-node");

        assertEquals("httpchunk", config.get("protocol"));
    }

    @Test
    void exposesRuntimeMetadataAndGeneratesCachedPhpResult() throws Exception {
        AtomicReference<GenerationRequest> captured = new AtomicReference<>();
        ScriptGeneratorService generators = new ScriptGeneratorService(List.of(
                phpProvider(captured)));
        ShellResultStore resultStore = new ShellResultStore();
        DisguiseService disguiseService = mock(DisguiseService.class);
        when(disguiseService.getDisguiseById("req")).thenReturn(disguise("req"));
        when(disguiseService.getDisguiseById("resp")).thenReturn(disguise("resp"));
        ShellGeneratorTools tools = tools(disguiseService, resultStore, generators);

        Map<String, Object> metadata = tools.getShellGeneratorMeta();
        assertTrue(((Map<?, ?>) metadata.get("runtimeGenerators")).containsKey("php"));

        Map<String, Object> result = tools.generatePhpWebShell(
                "req", "resp", "http", "portable", "X-Test", "secret", 202, "seed-a");

        assertEquals(true, result.get("success"));
        assertNotNull(result.get("resultId"));
        assertEquals("<?php echo 'fixture';", resultStore.getContent((String) result.get("resultId")));
        assertTrue(String.valueOf(result.get("tip")).contains("取回 PHP WebShell 代码"));

        GenerationRequest request = captured.get();
        assertNotNull(request);
        assertEquals(PuppetRuntime.PHP, request.getRuntime());
        assertEquals("webshell", request.getArtifactType());
        assertEquals("portable", request.getOptions().get("outputMode"));
        assertEquals("X-Test", request.getOptions().get("headerName"));
        assertEquals("secret", request.getOptions().get("headerValue"));
        assertEquals(202, request.getOptions().get("respCode"));
        assertEquals("seed-a", request.getOptions().get("seed"));
    }

    @Test
    void rejectsUnsupportedPhpProtocolAndIncompleteHeaderGuard() {
        ShellGeneratorTools tools = tools(mockDisguiseService(), new ShellResultStore(),
                new ScriptGeneratorService(List.of(phpProvider(new AtomicReference<>()))));

        IllegalArgumentException protocolError = assertThrows(IllegalArgumentException.class,
                () -> tools.generatePhpWebShell(
                        "req", "resp", "httpchunk", "compact", null, null, 200, null));
        assertTrue(protocolError.getMessage().contains("仅支持 http"));

        IllegalArgumentException headerError = assertThrows(IllegalArgumentException.class,
                () -> tools.generatePhpWebShell(
                        "req", "resp", "http", "compact", "X-Test", null, 200, null));
        assertTrue(headerError.getMessage().contains("必须同时设置"));
    }

    private static ShellGeneratorTools tools(DisguiseService disguiseService,
                                              ShellResultStore resultStore,
                                              ScriptGeneratorService generators) {
        return new ShellGeneratorTools(disguiseService, mock(DelegatingChatModel.class), resultStore,
                mock(PuppetService.class), generators);
    }

    private static DisguiseService mockDisguiseService() {
        DisguiseService service = mock(DisguiseService.class);
        when(service.getDisguiseById("req")).thenReturn(disguise("req"));
        when(service.getDisguiseById("resp")).thenReturn(disguise("resp"));
        return service;
    }

    private static Disguise disguise(String id) {
        Disguise disguise = new Disguise();
        disguise.setDisguiseId(id);
        return disguise;
    }

    private static ScriptGeneratorProvider phpProvider(AtomicReference<GenerationRequest> captured) {
        return new ScriptGeneratorProvider() {
            @Override
            public PuppetRuntime getRuntime() {
                return PuppetRuntime.PHP;
            }

            @Override
            public Map<String, Object> getMetadata() {
                return Map.of(
                        "runtime", "php",
                        "artifactTypes", List.of("webshell"),
                        "outputModes", List.of("compact", "packed", "portable"));
            }

            @Override
            public GeneratedArtifact generate(GenerationRequest request) {
                captured.set(request);
                return new GeneratedArtifact("<?php echo 'fixture';", "php",
                        "application/x-httpd-php",
                        Map.of("runtime", "php", "outputMode", request.getOptions().get("outputMode")),
                        List.of());
            }
        };
    }
}
