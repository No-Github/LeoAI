package org.leo.web.service;

import org.junit.jupiter.api.Test;
import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.core.session.PuppetNodeSession;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PuppetHostDiscoveryServiceTest {

    @Test
    void doesNotProbeDuringSessionCreationFlow() {
        ProbeNode node = new ProbeNode(List.of(Map.of("code", 200)));
        PuppetNodeSession session = session("session-1", node);
        session.bindHostId("host-a");

        assertEquals("host-a", session.getCurrentHostId());
        assertEquals(0, node.probes);
    }

    @Test
    void cacheSessionAlsoReturnsSnapshotWithoutProbing() {
        ProbeNode node = new ProbeNode(List.of(Map.of("code", 500, "msg", "offline")));
        PuppetNodeSession session = session("session-2", node);
        session.bindHostId("host-a");

        session.setCacheMode(true);
        assertEquals(0, node.probes);
    }

    private static PuppetNodeSession session(String sessionId, ProbeNode node) {
        PuppetNodeSession session = new PuppetNodeSession();
        session.setSessionId(sessionId);
        session.setPuppetNode(node);
        return session;
    }

    private static final class ProbeNode extends AbstractPuppetNode {
        private final ArrayDeque<Map<String, Object>> responses;
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
        public Set<String> getLoadedComponents() { return Set.of(); }

        @Override
        public Map<String, Object> invokeComponent(String componentId, Map<String, Object> params) {
            return Map.of();
        }

        @Override
        public void unloadComponent(String componentId) { }

    }
}
