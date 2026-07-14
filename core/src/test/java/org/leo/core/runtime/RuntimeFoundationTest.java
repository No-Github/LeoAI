package org.leo.core.runtime;

import org.junit.jupiter.api.Test;
import org.leo.core.component.runtime.ComponentArtifact;
import org.leo.core.component.runtime.ComponentDeliveryMode;
import org.leo.core.entity.Puppet;
import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.core.puppet.capability.BasicInfoCapable;
import org.leo.core.puppet.capability.PuppetNodeCapabilityRegistry;
import org.leo.core.rpc.PuppetOperation;
import org.leo.core.rpc.PuppetRpcRequest;
import org.leo.core.session.PuppetNodeSession;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeFoundationTest {

    @Test
    void keepsLegacyJavaDefaultAndRecognizesPhp() {
        assertEquals(PuppetRuntime.JAVA, PuppetRuntime.from(null));
        assertEquals(PuppetRuntime.JAVA, PuppetRuntime.from("  "));
        assertEquals(PuppetRuntime.PHP, PuppetRuntime.from(" PHP "));
        assertEquals(PuppetRuntime.UNKNOWN, PuppetRuntime.from("ruby"));
    }

    @Test
    void dynamicCapabilityCanDisableAStructurallySupportedInterface() {
        TrackingNode node = new TrackingNode();
        Puppet puppet = new Puppet();
        puppet.setType("php");
        node.setPuppet(puppet);
        node.setRuntimeProfile(RuntimeProfile.builder(PuppetRuntime.PHP)
                .version("8.2")
                .sapi("fpm-fcgi")
                .capabilities(new CapabilitySet(List.of(
                        CapabilityStatus.unavailable("basicInfo", "component disabled"))))
                .build());

        assertEquals(PuppetRuntime.PHP, node.getRuntime());
        assertFalse(PuppetNodeCapabilityRegistry.supports(node, BasicInfoCapable.class));
        assertFalse(PuppetNodeCapabilityRegistry.listSupported(node).contains("basicInfo"));
        assertEquals("component disabled",
                PuppetNodeCapabilityRegistry.getStatus(node, "basicInfo").getReason());
    }

    @Test
    void mapsLegacyOperationsAndProtectsRpcParams() {
        assertEquals(PuppetOperation.PING, PuppetOperation.fromLegacyMode(0));
        assertEquals(PuppetOperation.COMPONENT_INVOKE, PuppetOperation.fromLegacyMode(3));
        assertThrows(IllegalArgumentException.class, () -> PuppetOperation.fromLegacyMode(99));

        PuppetRpcRequest request = new PuppetRpcRequest(
                PuppetRpcRequest.CURRENT_PROTOCOL_VERSION,
                "request-1",
                PuppetOperation.PING,
                null,
                1L,
                null,
                null,
                Map.of("key", "value"));

        assertEquals("value", request.params().get("key"));
        assertThrows(UnsupportedOperationException.class,
                () -> request.params().put("other", "value"));
    }

    @Test
    void componentArtifactsAndNodeLifecycleDoNotLeakMutableOrJavaSpecificState() {
        byte[] source = new byte[]{1, 2, 3};
        ComponentArtifact artifact = new ComponentArtifact(
                "system.basic-info", "1.0.0", "sha256",
                PuppetRuntime.PHP, ComponentDeliveryMode.BUNDLED, source);
        source[0] = 9;
        byte[] exposed = artifact.getContent();
        exposed[1] = 9;
        assertArrayEquals(new byte[]{1, 2, 3}, artifact.getContent());

        TrackingNode node = new TrackingNode();
        PuppetNodeSession session = new PuppetNodeSession();
        session.setPuppetNode(node);
        session.close();
        assertTrue(node.closed);
    }

    private static final class TrackingNode extends AbstractPuppetNode implements BasicInfoCapable {
        private boolean closed;

        @Override
        public Set<String> getLoadedComponents() {
            return Set.of();
        }

        @Override
        public Map<String, Object> invokeComponent(String componentId, Map<String, Object> params) {
            return Map.of("code", 200);
        }

        @Override
        public Map<String, Object> testConnection() {
            return Map.of("code", 200);
        }

        @Override
        public void unloadComponent(String componentId) {
        }

        @Override
        public Map<String, Object> getBasicInfo() {
            return Map.of("code", 200);
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
