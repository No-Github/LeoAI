package org.leo.web.service;

import jakarta.servlet.http.HttpSession;
import org.leo.ai.channel.AiModelConfigService;
import org.leo.ai.platform.PlatformAiState;
import org.leo.ai.platform.PlatformAiStateStore;
import org.leo.ai.thread.AiConversationStoreService;
import org.leo.core.entity.AiModelConfig;
import org.leo.core.entity.AiSseEvent;
import org.leo.core.entity.AiThreadRecord;
import org.leo.core.entity.User;
import org.leo.web.exception.ApiException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 平台 AI 线程生命周期与查询用例。
 *
 * <p>只负责状态装载、线程 CRUD、通道切换和历史读取，不参与模型执行。
 */
@Service
public class PlatformAiThreadService {

    private static final String SESSION_ATTR_PLATFORM_AI_STATE_ID = "platformAiStateId";
    private final AiModelConfigService modelConfigService;
    private final AiConversationStoreService conversationStore;
    private final PlatformAiAgentRegistry agentRegistry;
    private final AiTurnProtocolService turnProtocolService;

    public PlatformAiThreadService(AiModelConfigService modelConfigService,
                                   AiConversationStoreService conversationStore,
                                   PlatformAiAgentRegistry agentRegistry,
                                   AiTurnProtocolService turnProtocolService) {
        this.modelConfigService = modelConfigService;
        this.conversationStore = conversationStore;
        this.agentRegistry = agentRegistry;
        this.turnProtocolService = turnProtocolService;
    }

    public void createAgent(HttpSession httpSession, User user,
                            Integer configId) {
        PlatformAiState state = recreateState(httpSession);
        state.resetRuntimeState();
        AiModelConfig config = resolveOptionalChannel(configId);
        if (config != null) state.setAiConfigId(config.getId());

        if (conversationStore.findThread(state.getStateId()) == null) {
            conversationStore.createPlatformThread(
                    user.getUserId(), httpSession.getId(), state.getStateId(),
                    "平台 AI", state.getCreatedAt(), config);
        }
        conversationStore.attachEventJournal(state.getStateId(), state);
    }

    public void switchChannel(PlatformAiState state, Integer configId) {
        if (state.isExecuting()) {
            throw ApiException.badRequest("平台 AI 正在执行中，请等待完成或先停止后再切换通道");
        }
        AiModelConfig config = resolveOptionalChannel(configId);
        state.setAiConfigId(config != null ? config.getId() : null);
        agentRegistry.evict(state.getStateId());
        conversationStore.updateConfig(state.getStateId(), config);
    }

    public List<Map<String, Object>> listThreads(User user) {
        List<AiThreadRecord> records = conversationStore.listPlatformThreads(user.getUserId());
        if (records == null) return List.of();
        return records.stream().map(record -> {
            PlatformAiState runtime = PlatformAiStateStore.get(record.getThreadId());
            long persistedLastActiveAt =
                    record.getLastActiveAt() != null ? record.getLastActiveAt() : 0L;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("threadId", record.getThreadId());
            item.put("title", record.getTitle());
            item.put("createdAt", record.getCreatedAt());
            item.put("lastActiveAt", runtime != null
                    ? Math.max(persistedLastActiveAt, runtime.getLastActiveAt())
                    : record.getLastActiveAt());
            item.put("messageCount",
                    record.getMessageCount() != null ? record.getMessageCount() : 0);
            item.put("runStatus",
                    runtime != null ? runtime.getRunStatus() : record.getRunStatus());
            item.put("executing", runtime != null && runtime.isExecuting());
            AiTurnProtocolService.ThreadSnapshot snapshot =
                    turnProtocolService.snapshotThread(
                    record.getThreadId(),
                    String.valueOf(item.get("runStatus")));
            if (snapshot != null) item.putAll(snapshot.toMap());
            item.put("configId", record.getConfigId());
            item.put("configName", record.getConfigName());
            item.put("configProtocol", record.getConfigProtocol());
            item.put("configModel", record.getConfigModel());
            return item;
        }).toList();
    }

    /** 返回属于指定用户的平台 AI 内存状态，不会创建新状态。 */
    public PlatformAiState stateForUser(User user, String threadId) {
        if (user == null || threadId == null || threadId.isBlank()) return null;
        boolean owned = listThreads(user).stream()
                .anyMatch(item -> threadId.equals(String.valueOf(item.get("threadId"))));
        return owned ? PlatformAiStateStore.get(threadId) : null;
    }

    public Map<String, Object> createThread(HttpSession httpSession, User user,
                                            String title, Integer configId) {
        String threadId = "platform-ai-" + UUID.randomUUID();
        PlatformAiState state = PlatformAiStateStore.create(threadId);
        httpSession.setAttribute(SESSION_ATTR_PLATFORM_AI_STATE_ID, threadId);
        AiModelConfig config = resolveOptionalChannel(configId);
        if (config != null) state.setAiConfigId(config.getId());

        String safeTitle = title != null && !title.isBlank() ? title : "新对话";
        conversationStore.createPlatformThread(
                user.getUserId(), httpSession.getId(), threadId,
                safeTitle, state.getCreatedAt(), config);
        conversationStore.attachEventJournal(threadId, state);

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("threadId", threadId);
        info.put("title", safeTitle);
        info.put("configId", state.getAiConfigId());
        return info;
    }

    public void deleteThread(HttpSession httpSession, User user, String threadId) {
        if (threadId == null || threadId.isBlank()) return;
        requireOwnedThread(user, threadId);
        PlatformAiState state = PlatformAiStateStore.get(threadId);
        if (state != null) {
            state.stopGeneration("线程已删除");
            PlatformAiStateStore.remove(threadId);
        }
        agentRegistry.evict(threadId);
        conversationStore.deleteThread(threadId);
        if (threadId.equals(httpSession.getAttribute(SESSION_ATTR_PLATFORM_AI_STATE_ID))) {
            httpSession.removeAttribute(SESSION_ATTR_PLATFORM_AI_STATE_ID);
        }
    }

    public void renameThread(User user, String threadId, String title) {
        if (threadId == null || threadId.isBlank()) return;
        requireOwnedThread(user, threadId);
        String safeTitle = title != null && !title.isBlank() ? title.trim() : "未命名对话";
        conversationStore.renameThread(threadId, safeTitle);
    }

    public PlatformAiState activateThread(
            HttpSession httpSession, User user, String threadId) {
        if (threadId == null || threadId.isBlank()) {
            throw ApiException.badRequest("缺少 threadId");
        }
        AiThreadRecord record = requireOwnedThread(user, threadId);
        PlatformAiState state = PlatformAiStateStore.get(threadId);
        if (state == null) state = PlatformAiStateStore.create(threadId);
        state.setAiConfigId(record.getConfigId());
        conversationStore.attachEventJournal(threadId, state);
        httpSession.setAttribute(SESSION_ATTR_PLATFORM_AI_STATE_ID, threadId);
        return state;
    }

    public Map<String, Object> events(User user, String threadId,
                                      Long requestedAfterSeq, Integer requestedLimit) {
        AiThreadRecord persisted = requireOwnedThread(user, threadId);
        PlatformAiState state = PlatformAiStateStore.get(threadId);
        long requestedCursor =
                requestedAfterSeq != null ? Math.max(0L, requestedAfterSeq) : 0L;
        long afterSeq = requestedCursor > 0L
                ? requestedCursor
                : Math.max(
                        state != null ? state.getCurrentRunStartSeq() : 0L,
                        conversationStore.findLatestTurnStartSeq(threadId));
        int limit = requestedLimit != null ? requestedLimit : 200;
        List<Map<String, Object>> events = new ArrayList<>();
        for (AiSseEvent event :
                conversationStore.listEventsAfter(threadId, afterSeq, limit)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("seq", event.seq());
            item.put("timestamp", event.timestamp());
            item.put("name", event.name());
            item.put("data", event.data());
            if (event.subagentInvocationId() != null) {
                item.put("subagentInvocationId", event.subagentInvocationId());
            }
            if (event.turnId() != null) item.put("turnId", event.turnId());
            if (event.itemId() != null) item.put("itemId", event.itemId());
            if (event.runId() != null) item.put("runId", event.runId());
            events.add(item);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("events", events);
        long lastSeq = Math.max(
                state != null ? state.getLastSseEventSeq() : 0L,
                conversationStore.findLastEventSeq(threadId));
        String runStatus = state != null && state.isExecuting()
                ? state.getRunStatus() : persisted.getRunStatus();
        AiTurnProtocolService.ThreadSnapshot protocolSnapshot =
                turnProtocolService.snapshotThread(threadId, runStatus);
        data.put("lastSeq", lastSeq);
        data.putAll(protocolSnapshot.toMap());
        if (state != null) {
            data.putAll(runtimeSnapshot(state));
            data.put("lastSeq", lastSeq);
            data.putAll(protocolSnapshot.toMap());
        } else {
            data.put("elapsedMs", 0L);
            data.put("stopReason", null);
        }
        return data;
    }

    public PlatformAiState currentState(HttpSession httpSession) {
        if (httpSession == null) return null;
        Object stateId = httpSession.getAttribute(SESSION_ATTR_PLATFORM_AI_STATE_ID);
        return stateId != null ? PlatformAiStateStore.get(String.valueOf(stateId)) : null;
    }

    public Map<String, Object> messages(User user, String threadId,
                                        Integer requestedOffset, Integer requestedLimit) {
        AiThreadRecord thread = requireOwnedThread(user, threadId);
        int offset = requestedOffset != null ? Math.max(0, requestedOffset) : 0;
        int limit = requestedLimit != null ? requestedLimit : 50;
        Map<String, Object> data = new HashMap<>();
        data.put("messages", conversationStore.listMessages(thread.getThreadId(), offset, limit));
        data.put("total", conversationStore.countMessages(thread.getThreadId()));
        data.put("offset", offset);
        data.put("limit", limit);
        return data;
    }

    public List<org.leo.core.entity.AiSubagentInvocation> subagentInvocations(
            User user, String threadId) {
        return conversationStore.listSubagentInvocations(
                requireOwnedThread(user, threadId).getThreadId());
    }

    public PlatformAiState getState(HttpSession httpSession) {
        Object stateId = httpSession.getAttribute(SESSION_ATTR_PLATFORM_AI_STATE_ID);
        if (!(stateId instanceof String id) || id.isBlank()) return null;
        return PlatformAiStateStore.get(id);
    }

    public PlatformAiState requireState(HttpSession httpSession, String message) {
        PlatformAiState state = getState(httpSession);
        if (state == null) throw ApiException.notFound(message);
        return state;
    }

    private Map<String, Object> runtimeSnapshot(PlatformAiState state) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("status", state.getRunStatus());
        payload.put("executing", state.isExecuting());
        payload.put("elapsedMs", 0L);
        payload.put("lastSeq", state.getLastSseEventSeq());
        payload.put("stopReason", state.getStopReason());
        return payload;
    }

    private AiModelConfig resolveChannel(Integer configId) {
        try {
            AiModelConfig config = modelConfigService.resolve(configId);
            if (config == null) {
                if (configId != null) {
                    throw ApiException.notFound("AI 模型不存在或已删除，configId: " + configId);
                }
                throw ApiException.notFound("未配置激活的 AI 模型，请先在设置中添加并激活一条");
            }
            return config;
        } catch (IllegalArgumentException | IllegalStateException error) {
            throw ApiException.notFound(error.getMessage());
        }
    }

    private AiModelConfig resolveOptionalChannel(Integer configId) {
        return configId != null ? resolveChannel(configId) : null;
    }

    private AiThreadRecord requireOwnedThread(User user, String threadId) {
        AiThreadRecord record = conversationStore.findThread(threadId);
        if (record == null
                || !AiConversationStoreService.SCOPE_PLATFORM.equals(record.getScope())) {
            throw ApiException.notFound("线程不存在");
        }
        if (user == null || user.getUserId() == null
                || !user.getUserId().equals(record.getUserId())) {
            throw ApiException.notFound("线程不存在");
        }
        return record;
    }

    public PlatformAiState requireOwnedRuntime(User user, String threadId) {
        AiThreadRecord record = requireOwnedThread(user, threadId);
        PlatformAiState state = PlatformAiStateStore.get(record.getThreadId());
        if (state == null) {
            state = PlatformAiStateStore.create(record.getThreadId());
            state.setAiConfigId(record.getConfigId());
        }
        conversationStore.attachEventJournal(record.getThreadId(), state);
        return state;
    }

    private PlatformAiState recreateState(HttpSession httpSession) {
        Object existing = httpSession.getAttribute(SESSION_ATTR_PLATFORM_AI_STATE_ID);
        if (existing instanceof String stateId && !stateId.isBlank()) {
            PlatformAiState existingState = PlatformAiStateStore.get(stateId);
            if (existingState != null) existingState.stopGeneration("平台 AI 会话已重建");
            PlatformAiStateStore.remove(stateId);
            agentRegistry.evict(stateId);
        }
        String stateId = "platform-ai-" + UUID.randomUUID();
        PlatformAiState state = PlatformAiStateStore.create(stateId);
        httpSession.setAttribute(SESSION_ATTR_PLATFORM_AI_STATE_ID, stateId);
        return state;
    }
}
