package org.leo.ai.agent;

import org.leo.ai.platform.PlatformAiStateStore;
import org.leo.core.ai.AiRuntimeState;
import org.leo.core.session.PuppetNodeSession;
import org.leo.core.session.PuppetNodeSessionContainer;
import org.springframework.stereotype.Component;

/** 将 Platform/Puppet memoryId 统一解析为运行时状态。 */
@Component
public class AgentRuntimeResolver {

    public AiRuntimeState resolve(
            AiToolAuthorizationPolicy.AgentScope scope, Object memoryId) {
        String value = memoryId != null ? String.valueOf(memoryId) : null;
        if (value == null || value.isBlank()) return null;
        if (scope == AiToolAuthorizationPolicy.AgentScope.PLATFORM) {
            return PlatformAiStateStore.get(value);
        }
        PuppetNodeSession session = resolvePuppetSession(value);
        if (session == null) return null;
        String threadId = threadId(value);
        return threadId != null ? session.getAiThread(threadId) : session.getActiveThread();
    }

    public AiRuntimeState resolveCurrent() {
        String sessionId = AiToolContext.getSessionId();
        if (sessionId == null || sessionId.isBlank()) return null;
        String threadId = AiToolContext.getThreadId();
        if (threadId == null || threadId.isBlank()) {
            return PlatformAiStateStore.get(sessionId);
        }
        PuppetNodeSession session = PuppetNodeSessionContainer.getSession(sessionId);
        return session != null ? session.getAiThread(threadId) : null;
    }

    public PuppetNodeSession resolvePuppetSession(Object memoryId) {
        if (memoryId == null) return null;
        String value = String.valueOf(memoryId);
        int separator = value.indexOf(':');
        String sessionId = separator > 0 ? value.substring(0, separator) : value;
        return PuppetNodeSessionContainer.getSession(sessionId);
    }

    private static String threadId(String memoryId) {
        int separator = memoryId.indexOf(':');
        return separator > 0 && separator < memoryId.length() - 1
                ? memoryId.substring(separator + 1) : null;
    }
}
