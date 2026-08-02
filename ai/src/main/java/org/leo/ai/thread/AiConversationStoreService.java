package org.leo.ai.thread;

import com.alibaba.fastjson.JSON;
import org.leo.ai.channel.DynamicModelProvider;
import org.leo.ai.runtime.AiTurnTrace;
import org.leo.core.entity.AiModelConfig;
import org.leo.core.entity.AiEventRecord;
import org.leo.core.entity.AiMessageRecord;
import org.leo.core.entity.AiRunRecord;
import org.leo.core.entity.AiThreadRecord;
import org.leo.core.entity.AiTurnRecord;
import org.leo.core.ai.AiRunStatus;
import org.leo.core.entity.AiUserInputRequest;
import org.leo.core.session.AiThread;
import org.leo.core.ai.AiEventStreamRuntime;
import org.leo.core.entity.AiSseEvent;
import org.leo.core.entity.AiThreadLeaseRecord;
import org.leo.core.entity.AiOrphanedRunRecord;
import org.leo.core.util.json.JsonUtil;
import org.leo.dao.mapper.AiConversationMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;

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
        row.setRunStatus(AiRunStatus.IDLE);
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
        row.setRunStatus(AiRunStatus.IDLE);
        mapper.insertThread(row);
    }

    public void renameThread(String threadId, String title) {
        mapper.renameThread(threadId, title, System.currentTimeMillis());
    }

    public void updateRuntime(String sessionId, AiThread thread) {
        updateRuntime(sessionId, thread, null);
    }

    public void updateRuntime(String sessionId, AiThread thread, String leaseToken) {
        if (thread == null) return;
        updateRuntime(sessionId, thread.getThreadId(), thread.getLastActiveAt(),
                thread.getRunStatus(), leaseToken);
    }

    public void updateRuntime(String sessionId, String threadId, long lastActiveAt, String runStatus) {
        updateRuntime(sessionId, threadId, lastActiveAt, runStatus, null);
    }

    public void updateRuntime(String sessionId, String threadId, long lastActiveAt,
                              String runStatus, String leaseToken) {
        long writeAt = System.currentTimeMillis();
        long activeAt = lastActiveAt > 0 ? lastActiveAt : writeAt;
        if (isBlank(leaseToken)) {
            AiThreadRecord row = new AiThreadRecord();
            row.setThreadId(threadId);
            row.setSessionId(sessionId);
            row.setLastActiveAt(activeAt);
            row.setRunStatus(runStatus);
            mapper.updateThreadRuntime(row);
            return;
        }
        requireFencedWrite(mapper.updateThreadRuntimeFenced(
                threadId, sessionId, activeAt, runStatus, leaseToken, writeAt),
                "线程运行状态", threadId);
    }

    public void updateConfig(String threadId, AiModelConfig config) {
        AiThreadRecord row = new AiThreadRecord();
        row.setThreadId(threadId);
        row.setLastActiveAt(System.currentTimeMillis());
        applyConfig(row, config);
        mapper.updateThreadConfig(row);
    }

    public ConversationCheckpoint findContextCheckpoint(String threadId) {
        AiThreadRecord thread = findThread(threadId);
        if (thread == null || isBlank(thread.getContextSummary())
                || isBlank(thread.getContextCheckpointJson())) {
            return null;
        }
        try {
            var metadata = JSON.parseObject(thread.getContextCheckpointJson());
            Integer version = metadata.getInteger("version");
            Long boundarySequence = metadata.getLong("boundarySequence");
            String boundaryHash = metadata.getString("boundaryHash");
            if (version == null || boundarySequence == null || isBlank(boundaryHash)) {
                return null;
            }
            return new ConversationCheckpoint(
                    thread.getContextSummary(), boundarySequence, boundaryHash, version);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public void updateContextCheckpoint(String threadId,
                                        String contextSummary,
                                        long boundarySequence,
                                        String boundaryHash,
                                        int version) {
        if (isBlank(threadId) || isBlank(contextSummary) || isBlank(boundaryHash)) return;
        String metadata = JsonUtil.toJsonString(Map.of(
                "version", version,
                "boundarySequence", boundarySequence,
                "boundaryHash", boundaryHash));
        mapper.updateThreadContextCheckpoint(
                threadId, contextSummary, metadata, System.currentTimeMillis());
    }

    public void clearContextCheckpoint(String threadId) {
        if (isBlank(threadId)) return;
        mapper.updateThreadContextCheckpoint(
                threadId, null, null, System.currentTimeMillis());
    }

    public void deleteThread(String threadId) {
        mapper.deleteThread(threadId);
    }

    // ── 持久化 Turn 控制协议 ────────────────────────────────────────────────

    public AiTurnRecord findProtocolTurnByClientId(
            String threadId, String clientUserMessageId) {
        if (isBlank(threadId) || isBlank(clientUserMessageId)) return null;
        return mapper.findTurnByClientMessage(
                threadId, clientUserMessageId.trim());
    }

    public AiTurnRecord findProtocolTurn(String turnId) {
        return isBlank(turnId) ? null : mapper.findTurnById(turnId);
    }

    public AiTurnRecord findNextQueuedProtocolTurn(String threadId) {
        if (isBlank(threadId)) return null;
        mapper.expireUserInputRequests(threadId.trim(), System.currentTimeMillis());
        return mapper.findNextQueuedTurn(threadId);
    }

    public List<AiTurnRecord> listInProgressProtocolTurns(String threadId) {
        return isBlank(threadId) ? List.of() : mapper.listInProgressTurns(threadId);
    }

    public List<String> listDispatchableProtocolThreadIds() {
        mapper.expireAllUserInputRequests(System.currentTimeMillis());
        return mapper.listDispatchableThreadIds();
    }

    /**
     * 原子接收用户命令。Turn 和两条可展示消息先于任何运行时对象持久化，
     * 因此刷新页面后 queued/running/cancelling 都可仅从数据库恢复。
     */
    @Transactional
    public boolean reserveProtocolTurn(AiTurnRecord turn,
                                       String userContent,
                                       Object attachments) {
        return reserveProtocolTurn(turn, userContent, attachments, null);
    }

    @Transactional
    public boolean reserveProtocolTurn(AiTurnRecord turn,
                                       String userContent,
                                       Object attachments,
                                       String answerToQuestionId) {
        if (turn == null || mapper.insertProtocolTurn(turn) != 1) return false;
        appendMessage(
                turn.getUserItemId(), turn.getThreadId(), turn.getTurnId(),
                null, MESSAGE_PENDING, "user", userContent,
                null, null, null, attachments);
        appendMessage(
                turn.getAssistantItemId(), turn.getThreadId(), turn.getTurnId(),
                null, MESSAGE_PENDING, "assistant", "",
                null, null, null, null);
        if (!isBlank(answerToQuestionId)) {
            AiUserInputRequest request = findUserInputRequest(answerToQuestionId);
            String normalizedAnswer = userContent != null ? userContent.trim() : "";
            if (request == null || !turn.getThreadId().equals(request.getThreadId())
                    || !AiUserInputRequest.STATUS_PENDING.equals(request.getStatus())) {
                throw new IllegalStateException("待回答问题不存在或已处理");
            }
            if (normalizedAnswer.isBlank()) {
                throw new IllegalStateException("用户回答不能为空");
            }
            if (!Boolean.TRUE.equals(request.getAllowFreeText())
                    && !request.optionValues().contains(normalizedAnswer)) {
                throw new IllegalStateException("回答必须从问题提供的选项中选择");
            }
            int answered = mapper.answerUserInputRequest(
                    answerToQuestionId.trim(), turn.getThreadId(),
                    normalizedAnswer,
                    System.currentTimeMillis());
            if (answered != 1) {
                throw new IllegalStateException(
                        "待回答问题不存在、已处理或已过期");
            }
        }
        return true;
    }

    // ── Agent 等待用户输入 ────────────────────────────────────────────────

    public AiUserInputRequest findUserInputRequest(String requestId) {
        return isBlank(requestId) ? null : mapper.findUserInputRequest(requestId.trim());
    }

    public AiUserInputRequest findPendingUserInputRequest(String threadId) {
        if (isBlank(threadId)) return null;
        String normalized = threadId.trim();
        mapper.expireUserInputRequests(normalized, System.currentTimeMillis());
        return mapper.findPendingUserInputRequest(normalized);
    }

    public AiUserInputRequest createUserInputRequest(AiUserInputRequest request) {
        if (request == null || isBlank(request.getThreadId())) {
            throw new IllegalArgumentException("用户输入请求缺少 threadId");
        }
        AiUserInputRequest pending = findPendingUserInputRequest(request.getThreadId());
        if (pending != null) return pending;
        try {
            if (mapper.insertUserInputRequest(request) != 1) {
                throw new IllegalStateException("创建用户输入请求失败");
            }
        } catch (DataIntegrityViolationException conflict) {
            AiUserInputRequest winner = findPendingUserInputRequest(request.getThreadId());
            if (winner != null) return winner;
            throw conflict;
        }
        return request;
    }

    /** 原子消费一次高风险操作确认，避免同一确认被并发工具调用复用。 */
    @Transactional
    public boolean consumeConfirmation(String requestId, String threadId,
                                       String toolName, String argumentsHash,
                                       long consumedAt) {
        if (isBlank(requestId) || isBlank(threadId)
                || isBlank(toolName) || isBlank(argumentsHash)) return false;
        return mapper.consumeConfirmation(requestId.trim(), threadId.trim(),
                toolName.trim(), argumentsHash.trim(), consumedAt) == 1;
    }

    public boolean claimProtocolTurnStart(String turnId, long startedAt) {
        return !isBlank(turnId)
                && mapper.markProtocolTurnStarted(turnId, startedAt) == 1;
    }

    @Transactional
    public AiTurnRecord requestProtocolTurnInterrupt(
            String threadId, String turnId) {
        mapper.requestProtocolTurnInterrupt(threadId, turnId);
        AiTurnRecord current = findProtocolTurn(turnId);
        if (current != null && "queued".equals(current.getDispatchStatus())) {
            AiTurnRecord completed = completeProtocolTurn(
                    turnId, "interrupted", null, System.currentTimeMillis());
            mapper.updateTurnMessageStatus(
                    threadId, turnId, MESSAGE_DISCARDED);
            Map<String, Object> turn = new LinkedHashMap<>();
            turn.put("id", turnId);
            turn.put("threadId", threadId);
            turn.put("status", "interrupted");
            turn.put("interruptRequested", true);
            appendEvent(threadId, new AiSseEvent(
                    mapper.findLastEventSeq(threadId) + 1L,
                    System.currentTimeMillis(),
                    "turn/completed",
                    Map.of("turn", turn),
                    null,
                    turnId,
                    current.getAssistantItemId(),
                    null));
            return completed;
        }
        return current;
    }

    public boolean hasInterruptRequestedTurn(String threadId) {
        return !isBlank(threadId)
                && mapper.countInterruptRequestedTurns(threadId) > 0;
    }

    public AiTurnRecord completeProtocolTurn(
            String turnId, String protocolStatus, String errorMessage, long completedAt) {
        return completeProtocolTurn(
                turnId, protocolStatus, errorMessage, completedAt, null);
    }

    public AiTurnRecord completeProtocolTurn(
            String turnId, String protocolStatus, String errorMessage,
            long completedAt, String leaseToken) {
        if (isBlank(leaseToken)) {
            AiTurnRecord row = new AiTurnRecord();
            row.setTurnId(turnId);
            row.setProtocolStatus(protocolStatus);
            row.setErrorMessage(emptyToNull(errorMessage));
            row.setCompletedAt(completedAt);
            mapper.completeProtocolTurn(row);
        } else {
            requireFencedWrite(mapper.completeProtocolTurnFenced(
                            turnId, protocolStatus, emptyToNull(errorMessage),
                            completedAt, leaseToken),
                    "协议 Turn 终结", turnId);
        }
        return findProtocolTurn(turnId);
    }

    public AiTurnRecord requeueProtocolTurn(String turnId) {
        if (!isBlank(turnId)) mapper.requeueProtocolTurn(turnId);
        return findProtocolTurn(turnId);
    }

    // ── 可重放事件日志 ───────────────────────────────────────────────────────

    /**
     * 将运行时绑定到数据库事件日志。绑定时先恢复线程级最新序号，保证进程重启后
     * 新事件仍沿用同一个单调游标。
     */
    public void attachEventJournal(String threadId, AiEventStreamRuntime runtime) {
        if (isBlank(threadId) || runtime == null) return;
        long lastSeq = mapper.findLastEventSeq(threadId);
        runtime.configureEventJournal(lastSeq, event ->
                appendEvent(threadId, event, runtime.getActiveLeaseToken()));
    }

    public void appendEvent(String threadId, AiSseEvent event) {
        appendEvent(threadId, event, null);
    }

    public void appendEvent(String threadId, AiSseEvent event, String leaseToken) {
        if (isBlank(threadId) || event == null || event.seq() <= 0L) return;
        String eventId = UUID.randomUUID().toString();
        int inserted = mapper.insertEvent(
                eventId,
                emptyToNull(event.runId()),
                threadId,
                emptyToNull(event.turnId()),
                emptyToNull(event.itemId()),
                emptyToNull(event.subagentInvocationId()),
                event.seq(),
                event.timestamp(),
                event.name(),
                JSON.toJSONString(event.data()),
                emptyToNull(leaseToken),
                System.currentTimeMillis());
        if (!isBlank(leaseToken)) {
            requireFencedWrite(inserted, "事件日志", eventId);
        }
    }

    public List<AiSseEvent> listEventsAfter(String threadId, long afterSeq, int limit) {
        if (isBlank(threadId)) return List.of();
        int safeLimit = Math.max(1, Math.min(limit > 0 ? limit : 200, 2000));
        return mapper.listEventsAfter(threadId, Math.max(0L, afterSeq), safeLimit)
                .stream()
                .map(this::toSseEvent)
                .toList();
    }

    public long findLastEventSeq(String threadId) {
        return isBlank(threadId) ? 0L : mapper.findLastEventSeq(threadId);
    }

    public boolean hasTurnCompletedEvent(String threadId, String turnId) {
        return isBlank(turnId)
                || mapper.countTurnCompletedEvents(threadId, turnId) > 0;
    }

    public long findLatestTurnStartSeq(String threadId) {
        return isBlank(threadId) ? 0L : mapper.findLatestTurnStartSeq(threadId);
    }

    public boolean hasLatestTurnCompletedEvent(String threadId) {
        return isBlank(threadId)
                || mapper.hasLatestTurnCompletedEvent(threadId) > 0;
    }

    // ── 跨实例执行租约与孤儿 Run 收口 ───────────────────────────────────────

    public boolean acquireThreadLease(AiThreadLeaseRecord lease) {
        return lease != null && mapper.acquireThreadLease(lease) == 1;
    }

    public boolean renewThreadLease(AiThreadLeaseRecord lease) {
        return lease != null && mapper.renewThreadLease(lease) == 1;
    }

    public boolean releaseThreadLease(AiThreadLeaseRecord lease) {
        return lease != null && mapper.releaseThreadLease(lease) == 1;
    }

    public List<AiThreadLeaseRecord> listExpiredThreadLeases(long now) {
        return mapper.listExpiredThreadLeases(now);
    }

    public List<String> listThreadsWithStuckRunningTurns(long now) {
        return mapper.listThreadsWithStuckRunningTurns(now);
    }

    public boolean claimExpiredThreadLease(AiThreadLeaseRecord expired,
                                           AiThreadLeaseRecord recovery) {
        if (expired == null || recovery == null) return false;
        return mapper.claimExpiredThreadLease(
                expired.getThreadId(), expired.getLeaseToken(),
                recovery.getOwnerId(), recovery.getLeaseToken(),
                recovery.getAcquiredAt(), recovery.getHeartbeatAt(),
                recovery.getExpiresAt()) == 1;
    }

    /**
     * 将失去执行实例的 running Run 原子收口，并写入可重放的 turn/completed。
     */
    @Transactional
    public List<AiSseEvent> recoverOrphanedRuns(String threadId, long finishedAt) {
        if (isBlank(threadId)) return List.of();
        String message = "执行实例心跳超时，任务已自动收口";
        List<AiSseEvent> completedEvents = new ArrayList<>();
        for (AiOrphanedRunRecord run : mapper.listRunningRuns(threadId)) {
            if (mapper.failOrphanedRun(run.getRunId(), finishedAt, message) != 1) {
                continue;
            }
            persistOrphanedPartialOutput(run);
            mapper.discardRunMessages(run.getRunId());
            mapper.discardOrphanedTurn(run.getTurnId(), finishedAt);
            mapper.failOrphanedThread(threadId, finishedAt);
            if (hasTurnCompletedEvent(threadId, run.getTurnId())) continue;

            Map<String, Object> error = new LinkedHashMap<>();
            error.put("message", message);
            error.put("category", "orphaned");
            Map<String, Object> turn = new LinkedHashMap<>();
            turn.put("id", run.getTurnId());
            turn.put("threadId", threadId);
            turn.put("status", "failed");
            turn.put("error", error);

            AiSseEvent event = new AiSseEvent(
                    mapper.findLastEventSeq(threadId) + 1L,
                    finishedAt,
                    "turn/completed",
                    Map.of("turn", turn),
                    null,
                    run.getTurnId(),
                    run.getAssistantMessageId(),
                    run.getRunId());
            appendEvent(threadId, event);
            completedEvents.add(event);
        }
        return completedEvents;
    }

    private void persistOrphanedPartialOutput(AiOrphanedRunRecord run) {
        if (run == null || isBlank(run.getAssistantMessageId())) return;
        StringBuilder output = new StringBuilder();
        for (AiEventRecord row : mapper.listEventsByRun(run.getRunId())) {
            if (!"delta".equals(row.getName()) || row.getDataJson() == null) continue;
            Object delta = JSON.parse(row.getDataJson());
            if (delta != null) output.append(delta);
        }
        if (output.isEmpty()) return;
        AiMessageRecord assistant = new AiMessageRecord();
        assistant.setMessageId(run.getAssistantMessageId());
        assistant.setStatus(MESSAGE_DISCARDED);
        assistant.setContent(output.toString());
        mapper.updateMessage(assistant);
    }

    private AiSseEvent toSseEvent(AiEventRecord row) {
        Object data = row.getDataJson() != null
                ? JSON.parse(row.getDataJson()) : null;
        return new AiSseEvent(
                row.getEventSeq() != null ? row.getEventSeq() : 0L,
                row.getTimestamp() != null ? row.getTimestamp() : 0L,
                row.getName(), data, row.getSubagentInvocationId(),
                row.getTurnId(), row.getItemId(), row.getRunId());
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

    private String appendMessage(String requestedMessageId,
                                 String threadId, String turnId, String runId, String status,
                                 String role, String content,
                                 List<Object> nodes,
                                 Map<String, Object> review,
                                 Object planSnapshot,
                                 Object attachments) {
        long now = System.currentTimeMillis();
        AiMessageRecord row = new AiMessageRecord();
        row.setMessageId(!isBlank(requestedMessageId)
                ? requestedMessageId.trim() : UUID.randomUUID().toString());
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
        return beginTurn(null, threadId, configId, input, userContent, attachments,
                startedAt, runtimeJson, trace);
    }

    /**
     * 使用接入层已经分配的 Turn ID 创建持久化 Turn。这样 turn/start 可以在模型线程
     * 真正启动前把稳定 ID 返回给客户端；旧调用点传 null 时仍由存储层生成。
     */
    @Transactional
    public PersistedTurn beginTurn(String requestedTurnId,
                                   String threadId,
                                   Integer configId,
                                   String input,
                                   String userContent,
                                   Object attachments,
                                   long startedAt,
                                   String runtimeJson,
                                   AiTurnTrace trace) {
        return beginTurn(
                requestedTurnId, null, null, threadId, configId, input,
                userContent, attachments, startedAt, runtimeJson, trace, null);
    }

    /**
     * 使用协议层预分配的稳定 user/assistant Item ID 创建 Turn。
     */
    @Transactional
    public PersistedTurn beginTurn(String requestedTurnId,
                                   String requestedUserItemId,
                                   String requestedAssistantItemId,
                                   String threadId,
                                   Integer configId,
                                   String input,
                                   String userContent,
                                   Object attachments,
                                   long startedAt,
                                   String runtimeJson,
                                   AiTurnTrace trace) {
        return beginTurn(
                requestedTurnId, requestedUserItemId, requestedAssistantItemId,
                threadId, configId, input, userContent, attachments,
                startedAt, runtimeJson, trace, null);
    }

    @Transactional
    public PersistedTurn beginTurn(String requestedTurnId,
                                   String requestedUserItemId,
                                   String requestedAssistantItemId,
                                   String threadId,
                                   Integer configId,
                                   String input,
                                   String userContent,
                                   Object attachments,
                                   long startedAt,
                                   String runtimeJson,
                                   AiTurnTrace trace,
                                   String leaseToken) {
        if (trace == null) {
            throw new IllegalArgumentException("trace 不能为空");
        }
        String turnId = requestedTurnId != null && !requestedTurnId.isBlank()
                ? requestedTurnId.trim() : UUID.randomUUID().toString();
        String runId = UUID.randomUUID().toString();
        trace.bind(turnId, runId);

        AiTurnRecord reservedTurn = mapper.findTurnById(turnId);
        if (reservedTurn == null) {
            AiTurnRecord turn = new AiTurnRecord();
            turn.setTurnId(turnId);
            turn.setThreadId(threadId);
            turn.setStatus(MESSAGE_PENDING);
            turn.setCreatedAt(startedAt);
            mapper.insertTurn(turn);
        }

        AiRunRecord run = new AiRunRecord();
        run.setRunId(runId);
        run.setThreadId(threadId);
        run.setTurnId(turnId);
        run.setStatus(AiRunStatus.RUNNING);
        run.setStartedAt(startedAt);
        run.setConfigId(configId);
        run.setInput(input);
        run.setRuntimeJson(runtimeJson);
        run.setTraceId(trace.traceId());
        run.setTraceJson(trace.toJson());
        run.setLeaseToken(emptyToNull(leaseToken));
        if (isBlank(leaseToken)) {
            if (mapper.insertRun(run) != 1) {
                throw new IllegalStateException("Run 创建失败: " + runId);
            }
        } else {
            requireFencedWrite(mapper.insertRunFenced(
                            run, System.currentTimeMillis()),
                    "Run 创建", runId);
        }

        String userMessageId;
        String assistantMessageId;
        if (reservedTurn != null
                && !isBlank(reservedTurn.getUserItemId())
                && !isBlank(reservedTurn.getAssistantItemId())) {
            int attached = mapper.attachRunToTurnMessages(
                    threadId, turnId, runId);
            if (attached != 2) {
                throw new IllegalStateException(
                        "Turn 预留消息不完整: " + turnId);
            }
            userMessageId = reservedTurn.getUserItemId();
            assistantMessageId = reservedTurn.getAssistantItemId();
        } else {
            userMessageId = appendMessage(
                    requestedUserItemId,
                    threadId, turnId, runId, MESSAGE_PENDING,
                    "user", userContent, null, null, null, attachments);
            assistantMessageId = appendMessage(
                    requestedAssistantItemId,
                    threadId, turnId, runId, MESSAGE_PENDING,
                    "assistant", "", null, null, null, null);
        }
        return new PersistedTurn(
                turnId, runId, threadId, userMessageId, assistantMessageId,
                startedAt, emptyToNull(leaseToken));
    }

    /** 持久化 Presenter 完成后的最终阶段轨迹；失败不应改变已经确定的 Turn 终态。 */
    public void updateRunTrace(PersistedTurn turn, AiTurnTrace trace) {
        if (turn == null || trace == null) return;
        if (isBlank(turn.leaseToken())) {
            AiRunRecord row = new AiRunRecord();
            row.setRunId(turn.runId());
            row.setTraceJson(trace.toJson());
            mapper.updateRunTrace(row);
            return;
        }
        requireFencedWrite(mapper.updateRunTraceFenced(
                        turn.runId(), trace.toJson(), turn.leaseToken(),
                        System.currentTimeMillis()),
                "Run Trace", turn.runId());
    }

    /**
     * 原子提交成功 Turn：写入 assistant 消息、提交用户消息并结束 Run。
     */
    @Transactional
    public void completeTurn(PersistedTurn turn, String output,
                             List<Object> nodes, Map<String, Object> review,
                             Object planSnapshot, int toolCallCount) {
        if (turn == null) return;
        updateAssistantMessage(
                turn, MESSAGE_COMMITTED, output, nodes, review, planSnapshot);
        updateTurnMessageStatus(turn, MESSAGE_COMMITTED);
        finishTurn(turn, MESSAGE_COMMITTED);
        finishRun(turn.runId(), AiRunStatus.COMPLETED, turn.startedAt(),
                output, null, null, null, toolCallCount, turn.leaseToken());
    }

    /**
     * 丢弃未完成 Turn 的上下文消息，同时保留记录供历史界面和审计追溯。
     */
    @Transactional
    public void discardTurn(PersistedTurn turn, String runStatus, String errorCategory,
                            String errorMessage, String rawErrorMessage, int toolCallCount) {
        discardTurn(turn, runStatus, errorCategory, errorMessage,
                rawErrorMessage, toolCallCount, "", List.of(), null);
    }

    /**
     * 中断/失败时保存已经产生的 assistant Item，状态仍为 discarded，
     * 因而不会进入后续模型的 committed memory。
     */
    @Transactional
    public void discardTurn(PersistedTurn turn,
                            String runStatus,
                            String errorCategory,
                            String errorMessage,
                            String rawErrorMessage,
                            int toolCallCount,
                            String partialOutput,
                            List<Object> partialNodes,
                            Object planSnapshot) {
        if (turn == null) return;
        updateAssistantMessage(
                turn, MESSAGE_DISCARDED, partialOutput,
                partialNodes, null, planSnapshot);
        updateTurnMessageStatus(turn, MESSAGE_DISCARDED);
        finishTurn(turn, MESSAGE_DISCARDED);
        finishRun(turn.runId(), runStatus, turn.startedAt(), null,
                errorCategory, errorMessage, rawErrorMessage, toolCallCount,
                turn.leaseToken());
    }

    private void updateAssistantMessage(PersistedTurn turn,
                                        String status,
                                        String content,
                                        List<Object> nodes,
                                        Map<String, Object> review,
                                        Object planSnapshot) {
        AiMessageRecord assistant = new AiMessageRecord();
        assistant.setMessageId(turn.assistantMessageId());
        assistant.setStatus(status);
        assistant.setContent(content != null ? content : "");
        assistant.setNodesJson(toJsonOrNull(nodes));
        assistant.setReviewJson(toJsonOrNull(review));
        assistant.setPlanJson(toJsonOrNull(planSnapshot));
        if (isBlank(turn.leaseToken())) {
            mapper.updateMessage(assistant);
            return;
        }
        requireFencedWrite(mapper.updateMessageFenced(
                        assistant.getMessageId(), assistant.getStatus(),
                        assistant.getContent(), assistant.getNodesJson(),
                        assistant.getReviewJson(), assistant.getPlanJson(),
                        turn.leaseToken(), System.currentTimeMillis()),
                "Assistant 消息", assistant.getMessageId());
    }

    private void updateTurnMessageStatus(PersistedTurn turn, String status) {
        if (isBlank(turn.leaseToken())) {
            mapper.updateTurnMessageStatus(turn.threadId(), turn.turnId(), status);
            return;
        }
        requireFencedRows(mapper.updateTurnMessageStatusFenced(
                        turn.threadId(), turn.turnId(), status,
                        turn.leaseToken(), System.currentTimeMillis()),
                "Turn 消息状态", turn.turnId());
    }

    private void finishTurn(PersistedTurn turn, String status) {
        int updated = mapper.finishTurn(
                turn.turnId(), status, System.currentTimeMillis(),
                emptyToNull(turn.leaseToken()));
        requireTerminalWrite(updated, turn.leaseToken(), "Turn", turn.turnId());
    }

    public List<Map<String, Object>> listMessages(String threadId, int offset, int limit) {
        int safeOffset = Math.max(0, offset);
        int safeLimit = limit < 0 ? Integer.MAX_VALUE : Math.max(1, Math.min(limit, 200));
        return toMessageMaps(mapper.listMessages(threadId, safeOffset, safeLimit));
    }

    public List<ConversationMessage> committedMessages(String threadId, int limit) {
        return mapper.recentMessages(threadId, Math.max(1, Math.min(limit, 200))).stream()
                .map(record -> new ConversationMessage(
                        record.getMessageSeq(), record.getRole(), record.getContent()))
                .toList();
    }

    public ConversationMessage committedMessage(String threadId, long messageSequence) {
        AiMessageRecord record = mapper.findCommittedMessageBySequence(threadId, messageSequence);
        return record == null ? null : new ConversationMessage(
                record.getMessageSeq(), record.getRole(), record.getContent());
    }

    public int countMessages(String threadId) {
        return mapper.countMessages(threadId);
    }

    /**
     * 查找指定线程最近一条带有 plan 快照的消息中的 plan_json，
     * 用于线程重启后恢复计划状态。
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
                           String rawErrorMessage, int toolCallCount,
                           String leaseToken) {
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
        row.setLeaseToken(emptyToNull(leaseToken));
        int updated = mapper.finishRun(row);
        requireTerminalWrite(updated, leaseToken, "Run", runId);
    }

    private static void requireFencedWrite(int updated, String target, String id) {
        if (updated != 1) {
            throw new IllegalStateException(
                    "执行租约已失效，拒绝写入过期" + target + ": " + id);
        }
    }

    private static void requireFencedRows(int updated, String target, String id) {
        if (updated < 1) {
            throw new IllegalStateException(
                    "执行租约已失效，拒绝写入过期" + target + ": " + id);
        }
    }

    private static void requireTerminalWrite(
            int updated, String leaseToken, String target, String id) {
        if (updated == 1) return;
        String reason = isBlank(leaseToken)
                ? "终态已由其他执行者确定"
                : "执行租约已失效或终态已由其他执行者确定";
        throw new IllegalStateException(reason + "，拒绝重复终结" + target + ": " + id);
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
            item.put("runStatus", record.getRunStatus());
            item.put("protocolStatus", record.getProtocolStatus());
            item.put("dispatchStatus", record.getDispatchStatus());
            item.put("protocolErrorMessage",
                    record.getProtocolErrorMessage());
            item.put("answerToQuestionId", record.getAnswerToQuestionId());
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

    private static String emptyToNull(String value) {
        return isBlank(value) ? null : value;
    }

    public record PersistedTurn(String turnId, String runId, String threadId,
                                String userMessageId, String assistantMessageId,
                                long startedAt, String leaseToken) {
    }

    public record ConversationMessage(Long sequence, String role, String content) {
        public ConversationMessage(String role, String content) {
            this(null, role, content);
        }
    }

    public record ConversationCheckpoint(String summary,
                                         long boundarySequence,
                                         String boundaryHash,
                                         int version) {
    }
}
