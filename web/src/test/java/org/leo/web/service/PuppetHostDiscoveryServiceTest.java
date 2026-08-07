package org.leo.web.service;

import org.junit.jupiter.api.Test;
import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.core.puppet.capability.LoadedComponentCacheCapable;
import org.leo.core.session.PuppetNodeSession;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PuppetHostDiscoveryServiceTest {

    @Test
    void collectsLoadBalancedHostsWithoutChangingCurrentSelection() {
        ProbeNode node = new ProbeNode(List.of(
                response("host-b", "ComponentB"), response("host-a", "ComponentA"),
                response("host-c", "ComponentC"), response("host-b", "ComponentB"),
                response("host-a", "ComponentA"), response("host-c", "ComponentC"),
                response("host-b", "ComponentB"), response("host-c", "ComponentC")));
        PuppetNodeSession session = session("session-1", node);
        session.setCurrentHostId("host-a");

        List<String> result = new PuppetHostDiscoveryService(null).discover(session);

        assertEquals("host-a", session.getCurrentHostId());
        assertEquals(List.of("host-a", "host-b", "host-c"), result);
        assertEquals(Set.of("host-a", "host-b", "host-c"), session.snapshotHostIds());
        assertEquals(Set.of("ComponentC"), node.componentsByHost.get("host-c"));
    }

    @Test
    void retainsKnownHostsWhenProbeFails() {
        ProbeNode node = new ProbeNode(List.of(Map.of("code", 500, "msg", "offline")));
        PuppetNodeSession session = session("session-2", node);
        session.setCurrentHostId("host-a");
        session.addHostId("host-b");

        assertEquals(List.of("host-a", "host-b"),
                new PuppetHostDiscoveryService(null).discover(session));
        assertEquals(1, node.probes);
    }

    private static Map<String, Object> response(String hostId, String component) {
        return Map.of("code", 200, "hostId", hostId, "components", List.of(component));
    }

    private static PuppetNodeSession session(String sessionId, ProbeNode node) {
        PuppetNodeSession session = new PuppetNodeSession();
        session.setSessionId(sessionId);
        session.setPuppetNode(node);
        return session;
    }

    private static final class ProbeNode extends AbstractPuppetNode
            implements LoadedComponentCacheCapable {
        private final ArrayDeque<Map<String, Object>> responses;
        private final Map<String, Set<String>> componentsByHost = new LinkedHashMap<>();
        private int probes;

        private ProbeNode(List<Map<String, Object>> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public Map<String, Object> testConnection() {
            probes++;
            return responses.isEmpty() ? Map.of("code", 500) : responses.removeFirst();
        }

        @Override
        public void addLoadedComponent(String hostId, Set<String> loadedComponents) {
            componentsByHost.put(hostId, loadedComponents);
        }

        @Override
        public Set<String> getLoadedComponents() { return Set.of(); }

        @Override
        public Map<String, Object> invokeComponent(String componentId, Map<String, Object> params) {
            return Map.of();
        }

        @Override
        public void unloadComponent(String componentId) { }
    }
}
