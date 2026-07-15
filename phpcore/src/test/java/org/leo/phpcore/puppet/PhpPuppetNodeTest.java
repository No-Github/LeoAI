package org.leo.phpcore.puppet;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.Disguise;
import org.leo.core.net.Communication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;
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
