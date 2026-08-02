package org.leo.core.session;

import org.junit.jupiter.api.Test;
import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.core.puppet.capability.HostScopedCapable;

import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PuppetNodeSessionHostIdTest {

    @Test
    void followsNodeRebindAndInvalidatesHostScopedCaches() {
        HostScopedNode node = new HostScopedNode();
        PuppetNodeSession session = new PuppetNodeSession();
        session.setSessionId("session-1");
        session.setPuppetNode(node);
        session.setCurrentHostId("host-1");
        session.setBasicInfo("host-1", Map.of("hostname", "old"));
        session.putAiContextValue("basic-info", Map.of("hostname", "old"));

        node.setHostId("host-2");

        assertEquals("host-2", session.getCurrentHostId());
        assertTrue(session.getAllHostIds().containsAll(Set.of("host-1", "host-2")));
        assertNull(session.getBasicInfo("host-1"));
        assertNull(session.getAiContextValue("basic-info"));
    }

    private static final class HostScopedNode extends AbstractPuppetNode implements HostScopedCapable {
        private String hostId;
        private Consumer<String> listener = ignored -> { };

        @Override
        public String getHostId() {
            return hostId;
        }

        @Override
        public void setHostId(String hostId) {
            this.hostId = hostId;
            listener.accept(hostId);
        }

        @Override
        public void setHostIdChangeListener(Consumer<String> listener) {
            this.listener = listener == null ? ignored -> { } : listener;
        }

        @Override
        public Set<String> getLoadedComponents() {
            return Set.of();
        }

        @Override
        public Map<String, Object> invokeComponent(
                String componentId, Map<String, Object> params) {
            return Map.of();
        }

        @Override
        public Map<String, Object> testConnection() {
            return Map.of("code", 200, "hostId", hostId);
        }

        @Override
        public void unloadComponent(String componentId) {
        }
    }
}
