package org.leo.web.service;

import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.core.puppet.capability.LoadedComponentCacheCapable;
import org.leo.core.session.PuppetNodeSession;
import org.leo.service.puppetnode.PuppetNodeFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Discovers the backend instances hidden behind one load-balanced session endpoint. */
@Service
public class PuppetHostDiscoveryService {

    static final int DEFAULT_PROBE_COUNT = 8;
    private static final Logger logger = LoggerFactory.getLogger(PuppetHostDiscoveryService.class);
    private final PuppetNodeFactory puppetNodeFactory;

    public PuppetHostDiscoveryService(PuppetNodeFactory puppetNodeFactory) {
        this.puppetNodeFactory = puppetNodeFactory;
    }

    /**
     * PING is side-effect free and does not enforce HostId affinity, so repeated probes can observe
     * different load-balancer backends without changing the session's currently selected HostId.
     */
    public List<String> discover(PuppetNodeSession session) {
        LinkedHashSet<String> discovered = knownHostIds(session);
        if (session == null || session.isCacheMode()) return new ArrayList<>(discovered);

        AbstractPuppetNode node = session.getPuppetNode();
        if (node == null) return new ArrayList<>(discovered);

        for (int probe = 0; probe < DEFAULT_PROBE_COUNT; probe++) {
            AbstractPuppetNode probeNode = null;
            try {
                probeNode = createProbeNode(node);
                Map<String, Object> result = probeNode.testConnection();
                if (!isSuccess(result)) break;
                String hostId = normalized(result.get("hostId"));
                if (hostId == null) continue;
                session.addHostId(hostId);
                discovered.add(hostId);
                if (node instanceof LoadedComponentCacheCapable componentCache) {
                    componentCache.addLoadedComponent(hostId, componentNames(result.get("components")));
                }
            } catch (Exception ex) {
                logger.debug("HostId 探测失败, sessionId={}, probe={}: {}",
                        session.getSessionId(), probe + 1, ex.getMessage());
                break;
            } finally {
                closeProbeNode(probeNode, node, session.getSessionId());
            }
        }
        return new ArrayList<>(discovered);
    }

    private AbstractPuppetNode createProbeNode(AbstractPuppetNode sessionNode) throws Exception {
        if (puppetNodeFactory == null || sessionNode.getPuppet() == null) return sessionNode;
        // 使用全新的传输连接，避免负载均衡 Cookie/连接复用把连续 PING 固定到同一后端。
        return puppetNodeFactory.createLiveNode(sessionNode.getPuppet(), sessionNode.getUser());
    }

    private void closeProbeNode(AbstractPuppetNode probeNode,
                                AbstractPuppetNode sessionNode,
                                String sessionId) {
        if (probeNode == null || probeNode == sessionNode) return;
        try {
            probeNode.close();
        } catch (Exception ex) {
            logger.debug("关闭 HostId 探测连接失败, sessionId={}: {}", sessionId, ex.getMessage());
        }
    }

    private LinkedHashSet<String> knownHostIds(PuppetNodeSession session) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (session == null) return ids;
        String current = normalized(session.getCurrentHostId());
        if (current != null) ids.add(current);
        ids.addAll(session.snapshotHostIds());
        return ids;
    }

    private boolean isSuccess(Map<String, Object> result) {
        if (result == null) return false;
        Object code = result.get("code");
        if (code instanceof Number number) return number.intValue() == 200;
        return "200".equals(String.valueOf(code));
    }

    private String normalized(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private Set<String> componentNames(Object value) {
        Set<String> names = new LinkedHashSet<>();
        if (value instanceof Collection<?> collection) {
            collection.forEach(item -> addName(names, item));
        } else if (value != null && value.getClass().isArray()) {
            for (int index = 0; index < Array.getLength(value); index++) {
                addName(names, Array.get(value, index));
            }
        }
        return names;
    }

    private void addName(Set<String> names, Object value) {
        String name = normalized(value);
        if (name != null) names.add(name);
    }
}
