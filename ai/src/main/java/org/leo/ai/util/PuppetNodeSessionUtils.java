package org.leo.ai.util;

import org.leo.ai.agent.AiToolException;
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
            throw AiToolException.userActionRequired(
                    "SESSION_EXPIRED",
                    "当前 Puppet 会话不存在或已过期。",
                    "不要重复调用；请向用户说明需要重新连接 Puppet。");
        }
        return session;
    }

    public static AbstractPuppetNode getPuppetNode(String sessionId) {
        return getSession(sessionId).getPuppetNode();
    }

    public static <T> T requireCapability(String sessionId, Class<T> capabilityType) {
        if (capabilityType == null) {
            throw AiToolException.fatal(
                    "CAPABILITY_TYPE_MISSING",
                    "工具没有声明所需的 Puppet 能力类型。",
                    null);
        }
        AbstractPuppetNode node = getPuppetNode(sessionId);
        if (node == null) {
            throw AiToolException.userActionRequired(
                    "PUPPET_UNAVAILABLE",
                    "当前会话没有可用的 Puppet 实体。",
                    "不要重复调用；请向用户说明需要重新连接或选择 Puppet。");
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
        throw AiToolException.modelCorrectable(
                "UNSUPPORTED_CAPABILITY",
                "当前 Puppet 类型不支持能力: "
                        + capabilityType.getSimpleName()
                        + " (type=" + nodeType + reason + ")",
                "查询当前 Puppet capabilities，选择已支持的工具或向用户说明能力限制。");
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
