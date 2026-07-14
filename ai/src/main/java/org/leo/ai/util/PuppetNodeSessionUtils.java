package org.leo.ai.util;

import org.leo.core.entity.AiRuntimeStats;
import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.core.puppet.capability.PuppetNodeCapabilityRegistry;
import org.leo.core.runtime.CapabilityStatus;
import org.leo.core.session.PuppetNodeSession;
import org.leo.core.session.PuppetNodeSessionContainer;

public class PuppetNodeSessionUtils {

    public static PuppetNodeSession getSession(String sessionId) {
        PuppetNodeSession session = PuppetNodeSessionContainer.getSession(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("会话不存在: " + sessionId);
        }
        return session;
    }

    public static AbstractPuppetNode getPuppetNode(String sessionId) {
        return getSession(sessionId).getPuppetNode();
    }

    public static <T> T requireCapability(String sessionId, Class<T> capabilityType) {
        if (capabilityType == null) {
            throw new IllegalArgumentException("capabilityType不能为空");
        }
        AbstractPuppetNode node = getPuppetNode(sessionId);
        if (node == null) {
            throw new IllegalArgumentException("Puppet实体不存在: " + sessionId);
        }
        if (PuppetNodeCapabilityRegistry.supports(node, capabilityType)) {
            return capabilityType.cast(node);
        }
        String nodeType = node.getPuppet() != null ? node.getPuppet().getType() : node.getClass().getSimpleName();
        String capabilityName = PuppetNodeCapabilityRegistry.capabilityName(capabilityType);
        CapabilityStatus status = capabilityName != null
                ? PuppetNodeCapabilityRegistry.getStatus(node, capabilityName) : null;
        String reason = status != null && status.getReason() != null && !status.getReason().isBlank()
                ? ", reason=" + status.getReason() : "";
        throw new IllegalArgumentException("当前 Puppet 类型不支持能力: "
                + capabilityType.getSimpleName() + " (type=" + nodeType + reason + ")");
    }

    public static Object getAiContextValue(String sessionId, String key) {
        return getSession(sessionId).getAiContextValue(key);
    }

    public static void putAiContextValue(String sessionId, String key, Object value) {
        getSession(sessionId).putAiContextValue(key, value);
    }

    public static void removeAiContextValue(String sessionId, String key) {
        getSession(sessionId).removeAiContextValue(key);
    }

    public static void removeAiContextByPrefix(String sessionId, String prefix) {
        getSession(sessionId).removeAiContextByPrefix(prefix);
    }

    public static AiRuntimeStats getAiRuntimeStats(String sessionId) {
        return getSession(sessionId).getAiRuntimeStats();
    }
}
