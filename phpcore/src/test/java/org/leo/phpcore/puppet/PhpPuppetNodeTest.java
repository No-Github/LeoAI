package org.leo.phpcore.puppet;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.Disguise;
import org.leo.core.net.Communication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;
import org.leo.core.puppet.capability.TerminalCapable;
import org.leo.core.puppet.capability.HttpSenderCapable;
import org.leo.core.puppet.capability.HttpProxyCapable;
import org.leo.core.puppet.capability.LocalForwardCapable;
import org.leo.core.puppet.capability.ReverseTunnelCapable;
import org.leo.core.puppet.capability.Socks5ProxyCapable;
import org.leo.core.puppet.capability.SqlCapable;
import org.leo.core.puppet.database.DatabaseConnectionSpec;
import org.leo.core.util.json.PortableJsonCodec;
import org.leo.phpcore.component.PhpComponentArtifactRegistry;
import org.leo.phpcore.rpc.PhpRpcClient;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhpPuppetNodeTest {

    @Test
    void invokesByDigestAndLoadsOnlyWhenTargetCacheMisses() throws Exception {
        List<Integer> methods = new ArrayList<>();
        Set<String> cachedKeys = new HashSet<>();
        Disguise portable = new PortableDisguise();
        Communication communication = bytes -> {
            Map<String, Object> request = PortableJsonCodec.decode(bytes);
            int method = ((Number) request.get("M")).intValue();
            methods.add(method);
            String componentKey = String.valueOf(request.get("componentKey"));
            if (method == 2) {
                assertEquals("BasicInfoComponent", request.get("componentName"));
                assertEquals(80, componentKey.length());
                assertFalse(request.containsKey("componentDigest"));
                assertTrue(String.valueOf(request.get("source")).startsWith("<?php"));
                cachedKeys.add(componentKey);
                return response(Map.of("code", 200, "cached", false));
            }
            if (!cachedKeys.contains(componentKey)) {
                return response(Map.of("code", 424));
            }
            return response(Map.of("code", 200,
                    "BasicInfo", Map.of("OSInfo", Map.of("OSName", "test"))));
        };
        PhpPuppetNode node = node(communication, portable);

        node.getBasicInfo();
        node.getBasicInfo();

        assertEquals(List.of(3, 2, 3, 3), methods);
        assertTrue(node.getLoadedComponents().contains("BasicInfoComponent"));
        assertTrue(node.getAvailableComponents().contains("PluginComponent"));
    }

    @Test
    void reloadsOnceWhenPreviouslyAvailableComponentDisappears() throws Exception {
        List<Integer> methods = new ArrayList<>();
        AtomicInteger invokes = new AtomicInteger();
        Disguise portable = new PortableDisguise();
        Communication communication = bytes -> {
            Map<String, Object> request = PortableJsonCodec.decode(bytes);
            int method = ((Number) request.get("M")).intValue();
            methods.add(method);
            if (method == 3 && invokes.getAndIncrement() == 1) {
                return response(Map.of("code", 424));
            }
            return response(Map.of("code", 200, "output", "ok"));
        };
        PhpPuppetNode node = node(communication, portable);

        node.execSimpleCommand("echo first");
        Map<String, Object> result = node.execSimpleCommand("echo second");

        assertEquals("ok", result.get("output"));
        assertEquals(List.of(3, 3, 2, 3), methods);
    }

    @Test
    void testConnectionUsesMethodZeroAndUnloadOnlyClearsPlatformState() throws Exception {
        List<Integer> methods = new ArrayList<>();
        Disguise portable = new PortableDisguise();
        Communication communication = bytes -> {
            Map<String, Object> request = PortableJsonCodec.decode(bytes);
            methods.add(((Number) request.get("M")).intValue());
            return response(Map.of("code", 200, "hostId", "php-host",
                    "components", List.of("FileComponent")));
        };
        PhpPuppetNode node = node(communication, portable);

        Map<String, Object> result = node.testConnection();
        assertEquals("php-host", result.get("hostId"));
        assertTrue(node.getLoadedComponents().contains("FileComponent"));
        node.unloadComponent("FileComponent");
        assertFalse(node.getLoadedComponents().contains("FileComponent"));
        assertEquals(List.of(0), methods);
    }

    @Test
    void exposesTerminalCapabilityAndForwardsTerminalActions() throws Exception {
        List<Map<String, Object>> invokes = new ArrayList<>();
        Disguise portable = new PortableDisguise();
        Communication communication = bytes -> {
            Map<String, Object> request = PortableJsonCodec.decode(bytes);
            if (((Number) request.get("M")).intValue() == 3) invokes.add(request);
            return response(Map.of("code", 200));
        };
        PhpPuppetNode node = node(communication, portable);

        assertTrue(node instanceof TerminalCapable);
        node.execCommand("write", "init", "terminal-1");
        node.execCommand("read", "read", "terminal-1");
        node.execCommand("resize", "120,40", "terminal-1");
        node.execCommand("stop", "", "terminal-1");

        assertEquals(List.of("write", "read", "resize", "stop"),
                invokes.stream().map(item -> String.valueOf(item.get("action"))).toList());
        assertTrue(invokes.stream().allMatch(item -> "ExecCommandComponent".equals(item.get("componentName"))));
        assertTrue(invokes.stream().allMatch(item -> "terminal-1".equals(item.get("processId"))));
    }

    @Test
    void exposesHttpSenderCapabilityAndForwardsStructuredAndRawRequests() throws Exception {
        List<Map<String, Object>> invokes = new ArrayList<>();
        Disguise portable = new PortableDisguise();
        Communication communication = bytes -> {
            Map<String, Object> request = PortableJsonCodec.decode(bytes);
            if (((Number) request.get("M")).intValue() == 3) invokes.add(request);
            return response(Map.of("code", 200, "statusCode", 202,
                    "bodyType", "text", "body", "accepted"));
        };
        PhpPuppetNode node = node(communication, portable);

        assertTrue(node instanceof HttpSenderCapable);
        assertTrue(node instanceof Socks5ProxyCapable);
        assertTrue(node instanceof HttpProxyCapable);
        assertTrue(node instanceof LocalForwardCapable);
        assertTrue(node instanceof ReverseTunnelCapable);
        node.httpRequest("PUT", "http://example.test/direct", Map.of("X-Test", "yes"),
                "direct-body", 1000, 2000, true);
        Map<String, Object> raw = node.sendRawHttp(
                "POST /raw HTTP/1.1\r\nHost: example.test\r\n\r\nraw-body",
                "example.test", 8080, false, false, 3000, 4000);

        assertEquals(2, invokes.size());
        assertTrue(invokes.stream().allMatch(item -> "HttpRequestComponent".equals(item.get("componentName"))));
        assertTrue(invokes.stream().allMatch(item -> "send".equals(item.get("action"))));
        assertEquals("PUT", invokes.get(0).get("method"));
        assertEquals("direct-body", invokes.get(0).get("body"));
        assertEquals("http://example.test:8080/raw", invokes.get(1).get("url"));
        assertEquals("raw-body", invokes.get(1).get("body"));
        assertEquals("http://example.test:8080/raw", raw.get("requestUrl"));
    }

    @Test
    void forwardsTheSharedDatabaseContractToThePhpComponent() throws Exception {
        List<Map<String, Object>> invokes = new ArrayList<>();
        Disguise portable = new PortableDisguise();
        Communication communication = bytes -> {
            Map<String, Object> request = PortableJsonCodec.decode(bytes);
            if (((Number) request.get("M")).intValue() == 3) invokes.add(request);
            return response(Map.of("code", 200, "columns", List.of(), "rows", List.of(),
                    "rowCount", 0, "affectedRows", 1));
        };
        PhpPuppetNode node = node(communication, portable);

        assertTrue(node instanceof SqlCapable);
        Map<String, Object> result = node.executeSql(DatabaseConnectionSpec.fromMap(Map.of(
                        "type", "sqlite", "variant", "file", "file", "/tmp/example.sqlite",
                        "username", "db-user", "password", "db-password")),
                "UPDATE inventory SET quantity = 2 WHERE id = 1");

        assertEquals(1, invokes.size());
        Map<String, Object> request = invokes.get(0);
        assertEquals("DatabaseComponent", request.get("componentName"));
        assertEquals("exec", request.get("action"));
        assertEquals("pdo", request.get("provider"));
        assertEquals("sqlite", request.get("pdoDriver"));
        assertEquals("sqlite:/tmp/example.sqlite", request.get("dsn"));
        assertEquals("db-user", request.get("username"));
        assertEquals("db-password", request.get("password"));
        assertEquals("UPDATE inventory SET quantity = 2 WHERE id = 1", request.get("sql"));
        assertEquals(1, ((Number) result.get("affectedRows")).intValue());
    }

    private PhpPuppetNode node(Communication communication, Disguise disguise) {
        PhpRpcClient client = new PhpRpcClient(communication,
                List.of(new RequestLayer("/", Map.of(), disguise)),
                List.of(new ResponseLayer(disguise)));
        return new PhpPuppetNode(client, new PhpComponentArtifactRegistry());
    }

    private byte[] response(Map<String, Object> data) {
        return PortableJsonCodec.encode(data);
    }

    private static final class PortableDisguise extends Disguise {
        @Override
        public byte[] encode(Map<String, Object> params) {
            return PortableJsonCodec.encode(params);
        }

        @Override
        public Map<String, Object> decode(byte[] data) {
            return new LinkedHashMap<>(PortableJsonCodec.decode(data));
        }
    }
}
