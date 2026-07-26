package org.leo.ai.thread;

import com.alibaba.fastjson.JSON;
import org.leo.ai.channel.DynamicModelProvider;
import org.leo.ai.runtime.AiTurnTrace;
import org.leo.core.entity.AiModelConfig;
import org.leo.core.entity.AiMessageRecord;
import org.leo.core.entity.AiRunRecord;
import org.leo.core.entity.AiThreadRecord;
import org.leo.core.entity.AiTurnRecord;
import org.leo.core.session.AiThread;
import org.leo.core.util.json.JsonUtil;
import org.leo.dao.mapper.AiConversationMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AiConversationStoreService {

    public static final String SCOPE_PUPPET = "puppet";
    public static final String SCOPE_PLATFORM = "platform";
    public static final String MESSAGE_PENDING = "pending";
    public static final String MESSAGE_COMMITTED = "committed";
    public static final String MESSAGE_DISCARDED = "discarded";
    public static final String ERROR_CANCELLED = "cancelled";
    public static final String ERROR_PERSISTENCE = "persistence";

    private final AiConversationMapper mapper;

    public AiConversationStoreService(AiConversationMapper mapper) {
        this.mapper = mapper;
    }

    public List<AiThreadRecord> listPuppetThreads(String userId, String puppetId) {
        if (isBlank(puppetId)) {
            return List.of();
        }
        return mapper.listThreads(SCOPE_PUPPET, userId, puppetId);
    }

    public List<AiThreadRecord> listPlatformThreads(String userId) {
        if (isBlank(userId)) {
            return List.of();
        }
        return mapper.listPlatformThreads(userId);
    }

    public AiThreadRecord findThread(String threadId) {
        if (isBlank(threadId)) {
            return null;
        }
        return mapper.findThread(threadId);
    }

    public void createPuppetThread(String userId, String puppetId, String sessionId,
                                   AiThread thread, AiModelConfig config) {
        AiThreadRecord row = new AiThreadRecord();
        row.setThreadId(thread.getThreadId());
        row.setScope(SCOPE_PUPPET);
        row.setUserId(userId);
        row.setPuppetId(puppetId);
        row.setSessionId(sessionId);
        row.setTitle(thread.getTitle());
        applyConfig(row, config);
        row.setCreatedAt(thread.getCreatedAt());
        row.setLastActiveAt(thread.getLastActiveAt());
        row.setMessageCount(0);
        row.setRunStatus(AiThread.STATUS_IDLE);
        row.setMode(thread.getMode());
        row.setParentThreadId(thread.getParentThreadId());
        mapper.insertThread(row);
    }

    public void createPlatformThread(String userId, String sessionId, String threadId,
                                     String title, long createdAt, AiModelConfig config) {
        AiThreadRecord row = new AiThreadRecord();
        row.setThreadId(threadId);
        row.setScope(SCOPE_PLATFORM);
        row.setUserId(userId);
        row.setSessionId(sessionId);
        row.setTitle(isBlank(title) ? "平台 AI" : title);
        applyConfig(row, config);
        row.setCreatedAt(createdAt > 0 ? createdAt : System.currentTimeMillis());
        row.setLastActiveAt(row.getCreatedAt());
        row.setMessageCount(0);
        row.setRunStatus(AiThread.STATUS_IDLE);
        row.setMode(AiThread.MODE_AUTO);
        mapper.insertThread(row);
    }

    /** 切换执行模式并刷新 last_active。 */
    public void updateMode(String threadId, String mode) {
        long now = System.currentTimeMillis();
        mapper.updateThreadMode(threadId, mode == null || mode.isBlank() ? AiThread.MODE_AUTO : mode, now);
    }

    public void renameThread(String threadId, String title) {
        mapper.renameThread(threadId, title, System.currentTimeMillis());
    }

    public void updateRuntime(String sessionId, AiThread thread) {
        AiThreadRecord row = new AiThreadRecord();
        row.setThreadId(thread.getThreadId());
        row.setSessionId(sessionId);
        row.setLastActiveAt(thread.getLastActiveAt());
        row.setRunStatus(thread.getRunStatus());
        mapper.updateThreadRuntime(row);
    }

    public void updateRuntime(String sessionId, String threadId, long lastActiveAt, String runStatus) {
        AiThreadRecord row = new AiThreadRecord();
        row.setThreadId(threadId);
        row.setSessionId(sessionId);
        row.setLastActiveAt(lastActiveAt > 0 ? lastActiveAt : System.currentTimeMillis());
        row.setRunStatus(runStatus);
        mapper.updateThreadRuntime(row);
    }

    public void updateConfig(String threadId, AiModelConfig config) {
        AiThreadRecord row = new AiThreadRecord();
        row.setThreadId(threadId);
        row.setLastActiveAt(System.currentTimeMillis());
        applyConfig(row, config);
        mapper.updateThreadConfig(row);
    }

    public void deleteThread(String threadId) {
        mapper.deleteThread(threadId);
    }

    // ── 子 Agent 派发记录 ───────────────────────────────────────────────────

    /** 写入一条 pending 派发记录，调用方需要先填好 invocationId / parentThreadId / task。 */
    public void insertSubagentInvocation(org.leo.core.entity.AiSubagentInvocation row) {
        if (row.getStatus() == null) {
            row.setStatus(org.leo.core.entity.AiSubagentInvocation.STATUS_PENDING);
        }
        if (row.getCreatedAt() == null) {
            row.setCreatedAt(System.currentTimeMillis());
        }
        mapper.insertSubagentInvocation(row);
    }

    /** 更新派发状态：running / completed / failed / cancelled，以及 summary、childThreadId。 */
    public void updateSubagentInvocation(org.leo.core.entity.AiSubagentInvocation row) {
        if (row.getInvocationId() == null) {
            throw new IllegalArgumentException("invocationId 不能为空");
        }
        if (row.getStatus() != null
                && !org.leo.core.entity.AiSubagentInvocation.STATUS_PENDING.equals(row.getStatus())
                && !org.leo.core.entity.AiSubagentInvocation.STATUS_RUNNING.equals(row.getStatus())
                && row.getCompletedAt() == null) {
            row.setCompletedAt(System.currentTimeMillis());
        }
        mapper.updateSubagentInvocation(row);
    }

    public List<org.leo.core.entity.AiSubagentInvocation> listSubagentInvocations(String parentThreadId) {
        if (parentThreadId == null || parentThreadId.isBlank()) {
            return java.util.Collections.emptyList();
        }
        return mapper.listSubagentInvocations(parentThreadId);
    }

    private String appendMessage(String threadId, String turnId, String runId, String status,
                                 String role, String content,
                                 List<Object> nodes,
                                 Map<String, Object> review,
                                 Object planSnapshot,
                                 Object attachments) {
        long now = System.currentTimeMillis();
        AiMessageRecord row = new AiMessageRecord();
        row.setMessageId(UUID.randomUUID().toString());
        row.setThreadId(threadId);
        row.setTurnId(turnId);
        row.setRunId(runId);
        row.setStatus(status);
        row.setRole(role);
        row.setContent(content);
        row.setTimestamp(now);
        row.setAttachmentsJson(toJsonOrNull(attachments));
        row.setNodesJson(toJsonOrNull(nodes));
        row.setReviewJson(toJsonOrNull(review));
        row.setPlanJson(toJsonOrNull(planSnapshot));
        mapper.insertMessage(row);
        mapper.refreshMessageCount(threadId, now);
        return row.getMessageId();
    }

    /**
     * 原子创建一次 Turn：先写入运行记录，再写入 pending 用户消息。
     * 这样消息永远可以追溯到所属 Run，执行队列拒绝也不会产生孤立消息。
     */
    @Transactional
    public PersistedTurn beginTurn(String threadId, Integer configId,
                                   String input, String userContent, Object attachments,
                                   long startedAt, String runtimeJson,
                                   AiTurnTrace trace) {
        if (trace == null) {
            throw new IllegalArgumentException("trace 不能为空");
        }
        String turnId = UUID.randomUUID().toString();
        String runId = UUID.randomUUID().toString();
        trace.bind(turnId, runId);

        AiTurnRecord turn = new AiTurnRecord();
        turn.setTurnId(turnId);
        turn.setThreadId(threadId);
        turn.setStatus(MESSAGE_PENDING);
        turn.setCreatedAt(startedAt);
        mapper.insertTurn(turn);

        AiRunRecord run = new AiRunRecord();
        run.setRunId(runId);
        run.setThreadId(threadId);
        run.setTurnId(turnId);
        run.setStatus(AiThread.STATUS_RUNNING);
        run.setStartedAt(startedAt);
        run.setConfigId(configId);
        run.setInput(input);
        run.setRuntimeJson(runtimeJson);
        run.setTraceId(trace.traceId());
        run.setTraceJson(trace.toJson());
        mapper.insertRun(run);

        String userMessageId = appendMessage(
                threadId, turnId, runId, MESSAGE_PENDING,
                "user", userContent, null, null, null, attachments);
        return new PersistedTurn(turnId, runId, threadId, userMessageId, startedAt);
    }

    /** 持久化 Presenter 完成后的最终阶段轨迹；失败不应改变已经确定的 Turn 终态。 */
    public void updateRunTrace(PersistedTurn turn, AiTurnTrace trace) {
        if (turn == null || trace == null) return;
        AiRunRecord row = new AiRunRecord();
        row.setRunId(turn.runId());
        row.setTraceJson(trace.toJson());
        mapper.updateRunTrace(row);
    }

    /**
     * 原子提交成功 Turn：写入 assistant 消息、提交用户消息并结束 Run。
     */
    @Transactional
    public void completeTurn(PersistedTurn turn, String output,
                             List<Object> nodes, Map<String, Object> review,
                             Object planSnapshot, int toolCallCount) {
        if (turn == null) return;
        appendMessage(turn.threadId(), turn.turnId(), turn.runId(), MESSAGE_COMMITTED,
                "assistant", output, nodes, review, planSnapshot, null);
        mapper.updateTurnMessageStatus(turn.threadId(), turn.turnId(), MESSAGE_COMMITTED);
        finishTurn(turn, MESSAGE_COMMITTED);
        finishRun(turn.runId(), AiThread.STATUS_COMPLETED, turn.startedAt(),
                output, null, null, null, toolCallCount);
    }

    /**
     * 丢弃未完成 Turn 的上下文消息，同时保留记录供历史界面和审计追溯。
     */
    @Transactional
    public void discardTurn(PersistedTurn turn, String runStatus, String errorCategory,
                            String errorMessage, String rawErrorMessage, int toolCallCount) {
        if (turn == null) return;
        mapper.updateTurnMessageStatus(turn.threadId(), turn.turnId(), MESSAGE_DISCARDED);
        finishTurn(turn, MESSAGE_DISCARDED);
        finishRun(turn.runId(), runStatus, turn.startedAt(), null,
                errorCategory, errorMessage, rawErrorMessage, toolCallCount);
    }

    private void finishTurn(PersistedTurn turn, String status) {
        AiTurnRecord row = new AiTurnRecord();
        row.setTurnId(turn.turnId());
        row.setStatus(status);
        row.setCompletedAt(System.currentTimeMillis());
        mapper.finishTurn(row);
    }

    public List<Map<String, Object>> listMessages(String threadId, int offset, int limit) {
        int safeOffset = Math.max(0, offset);
        int safeLimit = limit < 0 ? Integer.MAX_VALUE : Math.max(1, Math.min(limit, 200));
        return toMessageMaps(mapper.listMessages(threadId, safeOffset, safeLimit));
    }

    public List<ConversationMessage> committedMessages(String threadId, int limit) {
        return mapper.recentMessages(threadId, Math.max(1, Math.min(limit, 200))).stream()
                .map(record -> new ConversationMessage(record.getRole(), record.getContent()))
                .toList();
    }

    public int countMessages(String threadId) {
        return mapper.countMessages(threadId);
    }

    /**
     * 查找指定线程最近一条带有 plan 快照的消息中的 plan_json，
     * 用于线程重启后恢复计划状态（含每步的 preApproved 标志）。
     *
     * @return plan JSON 字符串，找不到则返回 null
     */
    public String findLatestPlanJson(String threadId) {
        if (threadId == null || threadId.isBlank()) return null;
        List<AiMessageRecord> recent = mapper.recentMessages(threadId, 50);
        if (recent == null) return null;
        for (AiMessageRecord record : recent) {
            String planJson = record.getPlanJson();
            if (planJson != null && !planJson.isBlank() && !"null".equals(planJson)) {
                return planJson;
            }
        }
        return null;
    }

    private void finishRun(String runId, String status, long startedAt, String output,
                           String errorCategory, String errorMessage,
                           String rawErrorMessage, int toolCallCount) {
        AiRunRecord row = new AiRunRecord();
        row.setRunId(runId);
        row.setStatus(status);
        long finishedAt = System.currentTimeMillis();
        row.setFinishedAt(finishedAt);
        row.setDurationMs(Math.max(0L, finishedAt - startedAt));
        row.setOutput(output);
        row.setErrorCategory(errorCategory);
        row.setErrorMessage(errorMessage);
        row.setRawErrorMessage(rawErrorMessage);
        row.setToolCallCount(toolCallCount);
        mapper.finishRun(row);
    }

    private static void applyConfig(AiThreadRecord row, AiModelConfig config) {
        if (config == null) {
            return;
        }
        row.setConfigId(config.getId());
        row.setConfigName(config.getName());
        row.setConfigProtocol(DynamicModelProvider.resolveProtocol(config));
        row.setConfigModel(config.getModel());
        row.setConfigBaseUrl(config.getBaseUrl());
        row.setConfigCompletionsPath(config.getCompletionsPath());
        row.setConfigMaxOutputTokens(config.getMaxOutputTokens());
    }

    private static List<Map<String, Object>> toMessageMaps(List<AiMessageRecord> records) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AiMessageRecord record : records) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("messageId", record.getMessageId());
            item.put("turnId", record.getTurnId());
            item.put("runId", record.getRunId());
            item.put("sequence", record.getMessageSeq());
            item.put("status", record.getStatus());
            item.put("role", record.getRole());
            item.put("content", record.getContent());
            item.put("timestamp", record.getTimestamp());
            putJson(item, "attachments", record.getAttachmentsJson());
            putJson(item, "nodes", record.getNodesJson());
            putJson(item, "review", record.getReviewJson());
            putJson(item, "plan", record.getPlanJson());
            result.add(item);
        }
        return result;
    }

    private static void putJson(Map<String, Object> target, String key, String json) {
        Object parsed = fromJson(json);
        if (parsed != null) {
            target.put(key, parsed);
        }
    }

    private static String toJsonOrNull(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list && list.isEmpty()) {
            return null;
        }
        if (value instanceof Map<?, ?> map && map.isEmpty()) {
            return null;
        }
        return JsonUtil.toJsonString(value);
    }

    private static Object fromJson(String json) {
        if (isBlank(json)) {
            return null;
        }
        try {
            return JSON.parse(json);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record PersistedTurn(String turnId, String runId, String threadId,
                                String userMessageId, long startedAt) {
    }

    public record ConversationMessage(String role, String content) {
    }
}
