package org.leo.web.service;

import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.service.TokenStream;
import org.leo.ai.agent.AiAgentFactory;
import org.leo.ai.agent.PuppetNodeAgent;
import org.leo.ai.channel.AiModelConfigService;
import org.leo.ai.channel.AiModelFailoverService;
import org.leo.ai.channel.DynamicModelProvider;
import org.leo.ai.config.AiAgentProperties;
import org.leo.ai.service.AiErrorClassifier;
import org.leo.ai.service.SessionWarmupService;
import org.leo.ai.thread.AiConversationStoreService;
import com.alibaba.fastjson.JSON;
import org.leo.core.entity.AiModelConfig;
import org.leo.core.entity.AiPlan;
import org.leo.core.entity.AiPlanStatus;
import org.leo.core.entity.AiRuntimeStats;
import org.leo.core.entity.AiChatAuditEntry;
import org.leo.core.entity.AiExecutionPolicy;
import org.leo.core.entity.AiSseEvent;
import org.leo.core.entity.AiThreadRecord;
import org.leo.core.session.AiThread;
import org.leo.core.session.PuppetNodeSession;
import org.leo.core.util.session.PuppetNodeSessionWorkDirUtil;
import org.leo.web.exception.ApiException;
import org.leo.web.util.AiControllerUtil;
import org.leo.web.util.AiSseEventPump;
import org.leo.web.util.AiStreamingCancellation;
import org.leo.web.util.ControllerUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Puppet AI 线程生命周期和持久化服务。
 *
 * <p>控制器只负责 HTTP/SSE 编排；线程恢复、索引维护、通道快照和历史消息持久化
 * 集中在这里，避免控制器继续膨胀。
 */
@Service
public class PuppetNodeAiThreadService {

    private static final Logger logger = LoggerFactory.getLogger(PuppetNodeAiThreadService.class);
    private final AiAgentFactory aiAgentFactory;
    private final AiModelConfigService modelConfigService;
    private final DynamicModelProvider dynamicModelProvider;
    private final AiModelFailoverService failoverService;
    private final AiErrorClassifier aiErrorClassifier;
    private final AiConversationStoreService conversationStore;
    private final SessionWarmupService sessionWarmupService;
    private final AiAgentProperties agentProperties;
    private final AiSseEventPump sseEventPump;
    private final ConcurrentMap<String, CachedPuppetNodeAgent> puppetNodeAgents = new ConcurrentHashMap<>();

    @Autowired
    public PuppetNodeAiThreadService(AiAgentFactory aiAgentFactory,
                                     AiModelConfigService modelConfigService,
                                     DynamicModelProvider dynamicModelProvider,
                                     AiModelFailoverService failoverService,
                                     AiErrorClassifier aiErrorClassifier,
                                     AiConversationStoreService conversationStore,
                                     SessionWarmupService sessionWarmupService,
                                     AiAgentProperties agentProperties,
                                     AiSseEventPump sseEventPump) {
        this.aiAgentFactory = aiAgentFactory;
        this.modelConfigService = modelConfigService;
        this.dynamicModelProvider = dynamicModelProvider;
        this.failoverService = failoverService;
        this.aiErrorClassifier = aiErrorClassifier;
        this.conversationStore = conversationStore;
        this.sessionWarmupService = sessionWarmupService;
        this.agentProperties = agentProperties;
        this.sseEventPump = sseEventPump;
    }

    public void executeChat(PuppetNodeSession session,
                            AiThread thread,
                            String threadId,
                            String messageForAgent,
                            AiChatAuditEntry audit,
                            SseEmitter emitter,
                            long startMs) {
        executeChat(session, thread, threadId, messageForAgent, audit, emitter, startMs, null);
    }

    public void executeChat(PuppetNodeSession session,
                            AiThread thread,
                            String threadId,
                            String messageForAgent,
                            AiChatAuditEntry audit,
                            SseEmitter emitter,
                            long startMs,
                            String reasoningEffort) {
        thread.markExecuting(Thread.currentThread());
        String memoryId = session.getSessionId() + ":" + threadId;
        String runId = null;
        AiTimelineRecorder recorder = new AiTimelineRecorder(
                (name, data) -> sendRecordedEventSafely(thread, emitter, name, data));
        List<AiSseEvent> eventLog = recorder.eventLog();
        thread.getSseEventQueue().clear();
        final AiSseEventPump.Handle queueDrain;
        try {
            queueDrain = startQueueDrain(thread, emitter, eventLog);
        } catch (RuntimeException e) {
            AiErrorClassifier.Classification classification = aiErrorClassifier.classify(e);
            failThread(thread, audit, classification.message(), startMs);
            AiControllerUtil.safeSendError(emitter, classification);
            thread.clearExecuting();
            return;
        }

        try {
            if (thread.isStopRequested()) {
                throw new InterruptedException("已停止");
            }
            CachedPuppetNodeAgent agentRuntime = threadAgent(session, thread, reasoningEffort);
            if (agentRuntime.failoverMessage() != null) {
                sendRecordedEventSafely(thread, emitter, "warn", agentRuntime.failoverMessage());
            }
            runId = conversationStore.startRun(threadId, agentRuntime.effectiveConfigId(),
                    messageForAgent, startMs, agentRuntime.runtimeJson());
            conversationStore.updateRuntime(session.getSessionId(), thread);
            sendRecordedEvent(thread, emitter, "status", AiThread.STATUS_RUNNING);

            // 跨轮 plan 持久化：新轮次启动时注入当前活跃 plan，前端 PlanBar 立即展示
            emitCurrentPlanAtTurnStart(thread);

            final String fRunId = runId;
            AtomicReference<StreamingHandle> handleRef = new AtomicReference<>();
            TokenStream stream = agentRuntime.agent().chat(memoryId, messageForAgent);
            thread.setStopCallback(() -> AiStreamingCancellation.cancelCaptured(handleRef));

            stream
                .onPartialThinkingWithContext((thinking, ctx) -> {
                    AiStreamingCancellation.capture(
                            handleRef, ctx.streamingHandle(), thread::isStopRequested);
                    if (!recorder.hasPendingThinking()) {
                        logger.info("[Thinking] 开始接收思考内容, memoryId={}", memoryId);
                    }
                    recorder.appendThinking(thinking.text());
                })
                .onPartialResponseWithContext((partial, ctx) -> {
                    AiStreamingCancellation.capture(
                            handleRef, ctx.streamingHandle(), thread::isStopRequested);
                    // 收到正文 token 意味着思考阶段结束，flush thinking buffer
                    recorder.appendVisibleDelta(partial.text());
                    recorder.flushDelta();
                })
                .onPartialToolCallWithContext((partial, ctx) -> {
                    AiStreamingCancellation.capture(
                            handleRef, ctx.streamingHandle(), thread::isStopRequested);
                    recorder.onBoundary();
                    Map<String, Object> toolData = AiToolEventFactory.buildToolDeltaEventData(partial);
                    sendRecordedEventSafely(thread, emitter, "tool_delta", toolData);
                })
                .beforeToolExecution(execution -> {
                    recorder.onBoundary();
                    if (thread.isStopRequested() || Thread.currentThread().isInterrupted()) {
                        throw new RuntimeException(new InterruptedException(
                                thread.getStopReason() != null ? thread.getStopReason() : "已停止"));
                    }
                    Map<String, Object> toolData = AiToolEventFactory.buildToolStartEventData(execution);
                    sendRecordedEventSafely(thread, emitter, "node", toolData);
                    recorder.recordExternal("node", toolData);
                })
                .onToolExecuted(execution -> {
                    try {
                        // 工具执行完毕意味着一轮思考结束，flush thinking buffer
                        recorder.flushThinking();
                        Map<String, Object> toolData = AiToolEventFactory.buildToolEventData(execution);
                        sendRecordedEventSafely(thread, emitter, "patch", toolData);
                        recorder.recordExternal("patch", toolData);
                    } catch (Exception e) {
                        logger.warn("构建工具事件失败: {}", e.getMessage());
                    }
                })
                .onCompleteResponse(response -> {
                    // 最终响应前 flush 残余 thinking + 闭合最后一段可见文本
                    recorder.onBoundary();
                    String output = recorder.reply();
                    // turnHolder[0] 在 try 块中构建，在 finally 中发送（跨块共享）
                    Object[] turnHolder = { null };
                    try {
                        failoverService.recordSuccess(agentRuntime.effectiveConfigId());
                        int toolCallCount = countToolCallEvents(eventLog);
                        audit.complete(output, toolCallCount, System.currentTimeMillis() - startMs);
                        finishRun(fRunId, AiThread.STATUS_COMPLETED, startMs, output, null, toolCallCount);

                        Map<String, Object> review = buildTurnReview(output, eventLog, System.currentTimeMillis() - startMs);
                        Map<String, Object> usage = buildUsageEvent(response);
                        Map<String, Object> turn = new LinkedHashMap<>();
                        if (!usage.isEmpty()) {
                            // 累计 token 用量到线程级统计
                            accumulateTokenUsage(thread, usage);
                            turn.put("usage", usage);
                        }
                        turn.put("review", review);
                        turnHolder[0] = turn;

                        stopAndFlushQueuedEvents(thread, emitter, eventLog, queueDrain);
                        persistAssistantMessage(session, threadId, output, eventLog, review, thread.getCurrentPlan());
                        thread.markCompleted();
                        conversationStore.updateRuntime(session.getSessionId(), thread);
                    } catch (Exception e) {
                        logger.warn("onCompleteResponse 后续处理异常: {}", e.getMessage(), e);
                        thread.markCompleted();
                    } finally {
                        // turn 事件合并 content + usage + review，必须最后发送（前端收到后关闭流）
                        @SuppressWarnings("unchecked")
                        Map<String, Object> turn = turnHolder[0] instanceof Map<?, ?> m
                                ? (Map<String, Object>) m : new LinkedHashMap<>();
                        turn.putIfAbsent("content", output);
                        sendRecordedEventSafely(thread, emitter, "status", AiThread.STATUS_COMPLETED);
                        sendRecordedEventSafely(thread, emitter, "turn", turn);
                        AiControllerUtil.safeComplete(emitter);
                        thread.clearExecuting();
                    }
                })
                .onError(error -> {
                    try {
                        recorder.flushDelta();
                        stopAndFlushQueuedEvents(thread, emitter, eventLog, queueDrain);
                        if (thread.isStopRequested()) {
                            thread.markCancelled();
                            String reason = thread.getStopReason() != null ? thread.getStopReason() : "已停止";
                            audit.fail(reason, System.currentTimeMillis() - startMs);
                            finishRun(fRunId, AiThread.STATUS_CANCELLED, startMs, null, reason, 0);
                            conversationStore.updateRuntime(session.getSessionId(), thread);
                            sendRecordedStatusSafely(thread, emitter, AiThread.STATUS_CANCELLED);
                            AiControllerUtil.safeSendError(emitter, reason);
                        } else {
                            AiErrorClassifier.Classification classification = aiErrorClassifier.classify(error);
                            failoverService.recordFailure(agentRuntime.effectiveConfigId(), classification);
                            String errMsg = classification.message();
                            thread.markFailed();
                            audit.fail(errMsg, System.currentTimeMillis() - startMs);
                            finishRun(fRunId, AiThread.STATUS_FAILED, startMs, null, errMsg, 0);
                            conversationStore.updateRuntime(session.getSessionId(), thread);
                            sendRecordedStatusSafely(thread, emitter, AiThread.STATUS_FAILED);
                            AiControllerUtil.safeSendError(emitter, classification);
                        }
                    } catch (Exception e) {
                        logger.warn("onError 处理异常: {}", e.getMessage(), e);
                    } finally {
                        AiControllerUtil.safeComplete(emitter);
                        thread.clearExecuting();
                    }
                })
                .start();

        } catch (Exception e) {
            if (thread.isStopRequested()) {
                stopAndFlushQueuedEvents(thread, emitter, eventLog, queueDrain);
                thread.markCancelled();
                String reason = thread.getStopReason() != null ? thread.getStopReason() : "已停止";
                audit.fail(reason, System.currentTimeMillis() - startMs);
                finishRun(runId, AiThread.STATUS_CANCELLED, startMs, null, reason, 0);
                conversationStore.updateRuntime(session.getSessionId(), thread);
                sendRecordedStatusSafely(thread, emitter, AiThread.STATUS_CANCELLED);
                AiControllerUtil.safeSendError(emitter, reason);
            } else {
                stopAndFlushQueuedEvents(thread, emitter, eventLog, queueDrain);
                AiErrorClassifier.Classification classification = aiErrorClassifier.classify(e);
                String errMsg = classification.message();
                failThread(thread, audit, errMsg, startMs);
                finishRun(runId, AiThread.STATUS_FAILED, startMs, null, errMsg, 0);
                conversationStore.updateRuntime(session.getSessionId(), thread);
                sendRecordedStatusSafely(thread, emitter, AiThread.STATUS_FAILED);
                AiControllerUtil.safeSendError(emitter, classification);
            }
            thread.clearExecuting();
        }
    }

    private void failThread(AiThread thread, AiChatAuditEntry audit, String message, long startMs) {
        thread.markFailed();
        audit.fail(message, System.currentTimeMillis() - startMs);
    }

    private void finishRun(String runId, String status, long startMs, String output,
                           String errorMessage, int toolCallCount) {
        if (runId != null) {
            conversationStore.finishRun(runId, status, startMs, output, errorMessage, toolCallCount);
        }
    }

    private void sendRecordedEvent(AiThread thread, SseEmitter emitter, String name, Object data) throws Exception {
        AiSseEvent event = thread.recordSseEvent(name, data, extractSubagentInvocationId(data));
        sendExistingEvent(emitter, event);
    }

    private void sendExistingEvent(SseEmitter emitter, AiSseEvent event) throws Exception {
        SseEmitter.SseEventBuilder builder = SseEmitter.event()
                .name(event.name());
        if (event.data() instanceof String s) {
            builder.data(s, org.springframework.http.MediaType.TEXT_PLAIN);
        } else {
            builder.data(event.data() != null ? event.data() : "");
        }
        if (event.seq() > 0) {
            builder.id(String.valueOf(event.seq()));
        }
        synchronized (emitter) {
            emitter.send(builder);
        }
    }

    private AiSseEventPump.Handle startQueueDrain(AiThread thread,
                                                  SseEmitter emitter,
                                                  List<AiSseEvent> eventLog) {
        return sseEventPump.start(
                "Puppet AI",
                thread.getSseEventQueue(),
                eventLog,
                thread::getRunStatus,
                event -> sendExistingEvent(emitter, event),
                heartbeat -> sendHeartbeat(emitter, heartbeat));
    }

    /** 旁路下发 heartbeat 事件（不入 eventLog、不分配 seq、不进持久化）。 */
    private void sendHeartbeat(SseEmitter emitter, Map<String, Object> payload) throws Exception {
        SseEmitter.SseEventBuilder builder = SseEmitter.event()
                .name("heartbeat")
                .data(payload != null ? payload : new LinkedHashMap<>());
        synchronized (emitter) {
            emitter.send(builder);
        }
    }

    private void stopAndFlushQueuedEvents(AiThread thread,
                                          SseEmitter emitter,
                                          List<AiSseEvent> eventLog,
                                          AiSseEventPump.Handle queueDrain) {
        sseEventPump.stop(queueDrain);
        AiSseEvent event;
        boolean sendAvailable = true;
        while ((event = thread.getSseEventQueue().poll()) != null) {
            eventLog.add(event);
            if (!sendAvailable) continue;
            try {
                sendExistingEvent(emitter, event);
            } catch (Exception e) {
                sendAvailable = false;
                logger.debug("Puppet AI SSE 队列 flush 停止: {}", e.getMessage());
            }
        }
    }

    private String extractSubagentInvocationId(Object data) {
        if (data instanceof Map<?, ?> map) {
            Object id = map.get("parentSubagentInvocationId");
            if (id == null) id = map.get("subagentInvocationId");
            if (id != null) {
                String text = String.valueOf(id);
                return text.isBlank() ? null : text;
            }
        }
        return null;
    }

    /** 新轮次启动时，如果存在正在执行的 plan，注入当前 plan 快照到 SSE 流，让前端 PlanBar 跨轮可见。 */
    private void emitCurrentPlanAtTurnStart(AiThread thread) {
        AiPlan plan = thread.getCurrentPlan();
        if (plan == null) return;
        // 只在 plan 正在执行时注入：避免 PLANNING / COMPLETED / FAILED 状态的 plan 跟随新轮次
        if (plan.getStatus() != AiPlanStatus.IN_PROGRESS) return;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("kind", "plan");
            payload.put("planId", plan.getPlanId());
            payload.put("title", plan.getTitle());
            payload.put("goal", plan.getGoal());
            payload.put("status", plan.getStatus().name());
            payload.put("steps", plan.getSteps());
            if (plan.getFinalSummary() != null) payload.put("finalSummary", plan.getFinalSummary());
            thread.offerSseEvent("node", payload);
        } catch (Exception ignored) {
            // best-effort
        }
    }

    private void sendRecordedEventSafely(AiThread thread, SseEmitter emitter, String name, Object data) {
        try {
            sendRecordedEvent(thread, emitter, name, data);
        } catch (Exception e) {
            // 客户端已断开连接，忽略
        }
    }

    private Map<String, Object> buildUsageEvent(dev.langchain4j.model.chat.response.ChatResponse response) {
        Map<String, Object> usage = new LinkedHashMap<>();
        if (response == null) return usage;
        if (response.id() != null) usage.put("id", response.id());
        if (response.modelName() != null) usage.put("model", response.modelName());
        if (response.finishReason() != null) usage.put("finishReason", response.finishReason().name().toLowerCase());
        dev.langchain4j.model.output.TokenUsage tokenUsage = response.tokenUsage();
        if (tokenUsage != null) {
            usage.put("inputTokens", tokenUsage.inputTokenCount());
            usage.put("outputTokens", tokenUsage.outputTokenCount());
            usage.put("totalTokens", tokenUsage.totalTokenCount());
            if (tokenUsage instanceof dev.langchain4j.model.openai.OpenAiTokenUsage openaiUsage) {
                var inputDetails = openaiUsage.inputTokensDetails();
                if (inputDetails != null && inputDetails.cachedTokens() != null) {
                    usage.put("cachedInputTokens", inputDetails.cachedTokens());
                }
                var outputDetails = openaiUsage.outputTokensDetails();
                if (outputDetails != null && outputDetails.reasoningTokens() != null) {
                    usage.put("reasoningTokens", outputDetails.reasoningTokens());
                }
            }
        }
        usage.put("timestamp", System.currentTimeMillis());
        return usage;
    }

    /**
     * 将本轮 token 用量累加到线程级统计，并在 usage 事件中附加累计数据。
     */
    private void accumulateTokenUsage(AiThread thread, Map<String, Object> usage) {
        long input = toLong(usage.get("inputTokens"));
        long output = toLong(usage.get("outputTokens"));
        long total = toLong(usage.get("totalTokens"));
        long cachedInput = toLong(usage.get("cachedInputTokens"));
        long reasoning = toLong(usage.get("reasoningTokens"));

        AiRuntimeStats stats = thread.getRuntimeStats();
        stats.accumulateTokenUsage(input, output, total, cachedInput, reasoning);

        // 附加累计数据到 usage 事件
        Map<String, Object> cumulative = new LinkedHashMap<>();
        cumulative.put("inputTokens", stats.getCumulativeInputTokens());
        cumulative.put("outputTokens", stats.getCumulativeOutputTokens());
        cumulative.put("totalTokens", stats.getCumulativeTotalTokens());
        cumulative.put("cachedInputTokens", stats.getCumulativeCachedInputTokens());
        cumulative.put("reasoningTokens", stats.getCumulativeReasoningTokens());
        cumulative.put("turnCount", stats.getTurnCount());
        usage.put("cumulative", cumulative);
    }

    private static long toLong(Object value) {
        if (value instanceof Number num) return num.longValue();
        return 0L;
    }

    private void sendRecordedStatusSafely(AiThread thread, SseEmitter emitter, String status) {
        thread.recordSseEvent("status", status);
        AiControllerUtil.safeSendStatus(emitter, status);
    }

    private Map<String, Object> runtimeSnapshot(AiThread thread, long startMs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", thread.getRunStatus());
        if (startMs > 0) {
            payload.put("elapsedMs", Math.max(0L, System.currentTimeMillis() - startMs));
        }
        payload.put("stopReason", thread.getStopReason());
        payload.put("lastSeq", thread.getLastSseEventSeq());
        payload.put("executing", thread.isExecuting());
        return payload;
    }

    public ThreadResolution ensureThreadReady(PuppetNodeSession session, String threadId, Integer configId) {
        AiThread thread = session.getAiThread(threadId);
        boolean restored = false;
        AiThreadRecord persisted = findPersistedThread(session, threadId);
        if (thread == null) {
            thread = restorePersistedThread(session, threadId, persisted);
            restored = thread != null;
        }
        Integer resolvedConfigId = resolveConfigId(configId, thread, persisted);
        AiModelConfig resolvedChannel;
        try {
            resolvedChannel = resolveOptionalChannel(resolvedConfigId);
        } catch (ApiException | IllegalArgumentException | IllegalStateException e) {
            boolean hasCheckpoint = thread != null && hasThreadCheckpoint(session, threadId);
            return new ThreadResolution(thread, restored, hasCheckpoint, e.getMessage());
        }
        if (resolvedChannel != null) {
            resolvedConfigId = resolvedChannel.getId();
        }
        String configError = validateConfigId(resolvedConfigId);
        boolean hasCheckpoint = thread != null && hasThreadCheckpoint(session, threadId);
        if (configError != null) {
            return new ThreadResolution(thread, restored, hasCheckpoint, configError);
        }
        if (thread != null && (thread.getAiConfigId() == null || configId != null)) {
            thread.setAiConfigId(resolvedConfigId);
            updateThreadConfig(session, thread, resolvedChannel);
        }
        // 异步预热：确保 basicInfo、OS 平台、环境变量缓存就绪
        sessionWarmupService.warmupAsync(session.getSessionId());
        return new ThreadResolution(thread, restored, hasCheckpoint, null);
    }

    public AiThread requireThread(PuppetNodeSession session, String threadId) {
        AiThread thread = session.getAiThread(threadId);
        if (thread == null) {
            throw ApiException.notFound("线程不存在，threadId: " + threadId);
        }
        return thread;
    }

    public Map<String, Object> listThreads(PuppetNodeSession session) {
        String userId = session.getCreateByUser();
        String puppetId = PuppetNodeSessionWorkDirUtil.resolvePuppetId(session);

        List<AiThread> memThreads = session.listAiThreads();
        List<Map<String, Object>> result = new ArrayList<>();
        Map<String, AiThread> memById = new LinkedHashMap<>();
        for (AiThread thread : memThreads) memById.put(thread.getThreadId(), thread);
        if (puppetId != null) {
            for (AiThreadRecord record : conversationStore.listPuppetThreads(userId, puppetId)) {
                AiThread thread = memById.remove(record.getThreadId());
                Map<String, Object> item = thread != null
                        ? threadToMap(thread, safeMessageCount(record.getMessageCount()))
                        : threadRecordToMap(record);
                item.put("configName", record.getConfigName());
                item.put("configProtocol", record.getConfigProtocol());
                item.put("configModel", record.getConfigModel());
                item.put("hasCheckpoint", hasThreadCheckpoint(session, record.getThreadId()));
                result.add(item);
            }
        }
        for (AiThread thread : memById.values()) {
            Map<String, Object> item = threadToMap(thread, conversationStore.countMessages(thread.getThreadId()));
            if (puppetId != null) {
                item.put("hasCheckpoint", hasThreadCheckpoint(session, thread.getThreadId()));
            }
            result.add(item);
        }

        if (puppetId == null) {
            for (AiThread thread : memThreads) {
                if (result.stream().noneMatch(item -> thread.getThreadId().equals(item.get("threadId")))) {
                    result.add(threadToMap(thread, 0));
                }
            }
        }

        result.sort((a, b) -> Long.compare(
                ControllerUtil.toLong(b.get("lastActiveAt")), ControllerUtil.toLong(a.get("lastActiveAt"))));

        HashMap<String, Object> data = new HashMap<>();
        data.put("threads", result);
        data.put("activeThreadId", session.getActiveThreadId());
        return data;
    }

    public Map<String, Object> createThread(PuppetNodeSession session, String requestedTitle, Integer configId) {
        return createThread(session, requestedTitle, configId, null);
    }

    public Map<String, Object> createThread(PuppetNodeSession session, String requestedTitle, Integer configId,
                                            String mode) {
        return createThread(session, requestedTitle, configId, mode, null);
    }

    /** 创建由平台 AI 派发的隔离子线程。子线程不会出现在 Puppet AI 的普通线程列表中。 */
    public Map<String, Object> createChildThread(PuppetNodeSession session, String requestedTitle,
                                                  Integer configId, String mode, String parentThreadId) {
        if (parentThreadId == null || parentThreadId.isBlank()) {
            throw ApiException.badRequest("缺少 parentThreadId");
        }
        return createThread(session, requestedTitle, configId, mode, parentThreadId.trim());
    }

    private Map<String, Object> createThread(PuppetNodeSession session, String requestedTitle, Integer configId,
                                             String mode, String parentThreadId) {
        String threadId = UUID.randomUUID().toString();
        String title = requestedTitle;
        if (title == null || title.isBlank()) {
            title = "对话 " + (session.listAiThreads().size() + 1);
        }

        AiModelConfig config = resolveOptionalChannel(configId);
        Integer resolvedConfigId = config != null ? config.getId() : null;

        AiThread thread = session.createAiThread(threadId, title);
        thread.setAiConfigId(resolvedConfigId);
        thread.setMode(mode);
        thread.setParentThreadId(parentThreadId);

        // 异步预热：预填 basicInfo、OS 平台、环境变量缓存
        sessionWarmupService.warmupAsync(session.getSessionId());

        String userId = session.getCreateByUser();
        String puppetId = PuppetNodeSessionWorkDirUtil.resolvePuppetId(session);
        if (puppetId != null) {
            conversationStore.createPuppetThread(userId, puppetId, session.getSessionId(), thread, config);
        }

        HashMap<String, Object> info = new HashMap<>();
        info.put("threadId", threadId);
        info.put("title", title);
        info.put("configId", resolvedConfigId);
        info.put("mode", thread.getMode());
        info.put("parentThreadId", parentThreadId);
        info.put("reconSummaryLoaded", session.hasReconSummary());
        return info;
    }

    /**
     * 在调用线程内执行一次平台 AI 委派任务，并完整写入 Puppet AI 的线程、消息和运行记录。
     * 同步执行保证工具返回给平台 Agent 时已经得到可用于继续推理的最终摘要。
     */
    public Map<String, Object> executeDelegatedChat(PuppetNodeSession session,
                                                     AiThread thread,
                                                     String userMessage,
                                                     String messageForAgent,
                                                     AiChatAuditEntry audit,
                                                     String subagentInvocationId,
                                                     Consumer<AiSseEvent> eventSink) {
        if (session == null || thread == null) {
            throw ApiException.badRequest("Puppet AI 会话或线程不存在");
        }
        if (!thread.claimExecution()) {
            throw ApiException.badRequest("目标 Puppet AI 线程正在执行中");
        }

        long startMs = System.currentTimeMillis();
        String threadId = thread.getThreadId();
        String memoryId = session.getSessionId() + ":" + threadId;
        String runId = null;
        List<AiSseEvent> eventLog = new ArrayList<>();
        AiTimelineRecorder recorder = new AiTimelineRecorder((name, data) ->
                recordDelegatedEvent(thread, subagentInvocationId, name, data, eventLog, eventSink));
        try {
            thread.markExecuting(Thread.currentThread());
            thread.touchLastActiveAt();
            persistMessage(session, threadId, "user", userMessage);

            CachedPuppetNodeAgent agentRuntime = threadAgent(session, thread);
            if (agentRuntime.failoverMessage() != null) {
                thread.offerSystemWarn(agentRuntime.failoverMessage());
            }
            runId = conversationStore.startRun(threadId, agentRuntime.effectiveConfigId(),
                    messageForAgent, startMs, agentRuntime.runtimeJson());
            conversationStore.updateRuntime(session.getSessionId(), thread);
            recordDelegatedEvent(thread, subagentInvocationId, "status", AiThread.STATUS_RUNNING,
                    eventLog, eventSink);

            CompletableFuture<String> completion = new CompletableFuture<>();
            AtomicReference<StreamingHandle> handleRef = new AtomicReference<>();
            TokenStream stream = agentRuntime.agent().chat(memoryId, messageForAgent);
            thread.setStopCallback(() -> AiStreamingCancellation.cancelCaptured(handleRef));
            stream
                    .onPartialThinkingWithContext((thinking, ctx) -> {
                        AiStreamingCancellation.capture(
                                handleRef, ctx.streamingHandle(), thread::isStopRequested);
                        recorder.appendThinking(thinking.text());
                    })
                    .onPartialResponseWithContext((partial, ctx) -> {
                        AiStreamingCancellation.capture(
                                handleRef, ctx.streamingHandle(), thread::isStopRequested);
                        recorder.appendVisibleDelta(partial.text());
                        recorder.flushDelta();
                    })
                    .onPartialToolCallWithContext((partial, ctx) -> {
                        AiStreamingCancellation.capture(
                                handleRef, ctx.streamingHandle(), thread::isStopRequested);
                        recorder.onBoundary();
                        recordDelegatedEvent(thread, subagentInvocationId, "tool_delta",
                                AiToolEventFactory.buildToolDeltaEventData(partial), eventLog, eventSink);
                    })
                    .beforeToolExecution(execution -> {
                        recorder.onBoundary();
                        if (thread.isStopRequested()) {
                            throw new RuntimeException(new InterruptedException(
                                    thread.getStopReason() != null ? thread.getStopReason() : "已停止"));
                        }
                        recordDelegatedEvent(thread, subagentInvocationId, "node",
                                AiToolEventFactory.buildToolStartEventData(execution), eventLog, eventSink);
                    })
                    .onToolExecuted(execution -> {
                        recorder.flushThinking();
                        recordDelegatedEvent(thread, subagentInvocationId, "patch",
                                AiToolEventFactory.buildToolEventData(execution), eventLog, eventSink);
                        drainDelegatedQueuedEvents(thread, subagentInvocationId, eventLog, eventSink);
                    })
                    .onCompleteResponse(response -> {
                        recorder.onBoundary();
                        drainDelegatedQueuedEvents(thread, subagentInvocationId, eventLog, eventSink);
                        completion.complete(recorder.reply());
                    })
                    .onError(completion::completeExceptionally)
                    .start();

            String output = completion.get();
            failoverService.recordSuccess(agentRuntime.effectiveConfigId());
            if (audit != null) {
                audit.complete(output, 0, System.currentTimeMillis() - startMs);
            }
            finishRun(runId, AiThread.STATUS_COMPLETED, startMs, output, null, 0);
            Map<String, Object> review = buildTurnReview(output, eventLog,
                    System.currentTimeMillis() - startMs);
            persistAssistantMessage(session, threadId, output, eventLog, review, thread.getCurrentPlan());
            thread.markCompleted();
            thread.touchLastActiveAt();
            conversationStore.updateRuntime(session.getSessionId(), thread);
            recordDelegatedEvent(thread, subagentInvocationId, "status", AiThread.STATUS_COMPLETED,
                    eventLog, eventSink);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("sessionId", session.getSessionId());
            result.put("threadId", threadId);
            result.put("status", AiThread.STATUS_COMPLETED);
            result.put("summary", output);
            return result;
        } catch (Throwable error) {
            boolean cancelled = thread.isStopRequested() || Thread.currentThread().isInterrupted();
            String status = cancelled ? AiThread.STATUS_CANCELLED : AiThread.STATUS_FAILED;
            String message;
            if (cancelled) {
                message = thread.getStopReason() != null ? thread.getStopReason() : "已停止";
                thread.stop(message);
                thread.markCancelled();
            } else {
                AiErrorClassifier.Classification classification = aiErrorClassifier.classify(error);
                message = classification.message();
                failoverService.recordFailure(thread.getAiConfigId(), classification);
                thread.markFailed();
            }
            if (audit != null) {
                audit.fail(message, System.currentTimeMillis() - startMs);
            }
            finishRun(runId, status, startMs, null, message, 0);
            conversationStore.updateRuntime(session.getSessionId(), thread);
            recordDelegatedEvent(thread, subagentInvocationId, "status", status, eventLog, eventSink);
            throw new IllegalStateException(message, error);
        } finally {
            thread.clearExecuting();
        }
    }

    private void recordDelegatedEvent(AiThread thread,
                                      String subagentInvocationId,
                                      String name,
                                      Object data,
                                      List<AiSseEvent> eventLog,
                                      Consumer<AiSseEvent> eventSink) {
        AiSseEvent event = thread.recordSseEvent(name, data, subagentInvocationId);
        eventLog.add(event);
        if (eventSink != null) eventSink.accept(event);
    }

    private void drainDelegatedQueuedEvents(AiThread thread,
                                            String subagentInvocationId,
                                            List<AiSseEvent> eventLog,
                                            Consumer<AiSseEvent> eventSink) {
        AiSseEvent queued;
        while ((queued = thread.getSseEventQueue().poll()) != null) {
            AiSseEvent event = queued.subagentInvocationId() != null
                    ? queued : queued.withSubagent(subagentInvocationId);
            eventLog.add(event);
            if (eventSink != null) eventSink.accept(event);
        }
    }

    public void deleteThread(PuppetNodeSession session, String threadId) {
        session.removeAiThread(threadId);
        puppetNodeAgents.remove(agentCacheKey(session, threadId));

        String puppetId = PuppetNodeSessionWorkDirUtil.resolvePuppetId(session);
        if (puppetId != null) {
            conversationStore.deleteThread(threadId);
            PuppetNodeSessionWorkDirUtil.deleteAiThreadCheckpoints(session.getCreateByUser(), puppetId, threadId);
        }
    }

    public void renameThread(PuppetNodeSession session, String threadId, String title) {
        AiThread thread = session.getAiThread(threadId);
        if (thread != null) {
            thread.setTitle(title);
        }
        conversationStore.renameThread(threadId, title);
    }

    public Map<String, Object> threadMessages(PuppetNodeSession session, String threadId,
                                              Integer requestedOffset, Integer requestedLimit) {
        int offset = requestedOffset != null ? requestedOffset : 0;
        int limit = requestedLimit != null ? requestedLimit : 50;
        List<Map<String, Object>> messages = conversationStore.listMessages(threadId, offset, limit);
        int total = conversationStore.countMessages(threadId);

        HashMap<String, Object> data = new HashMap<>();
        data.put("messages", messages);
        data.put("total", total);
        data.put("offset", offset);
        data.put("limit", limit);
        return data;
    }

    public Map<String, Object> threadEvents(PuppetNodeSession session, String threadId,
                                            Long requestedAfterSeq, Integer requestedLimit) {
        AiThread thread = requireThread(session, threadId);
        long afterSeq = requestedAfterSeq != null ? Math.max(0L, requestedAfterSeq) : 0L;
        int limit = requestedLimit != null ? requestedLimit : 200;

        List<Map<String, Object>> events = new ArrayList<>();
        for (AiSseEvent event : thread.recentSseEventsAfter(afterSeq, limit)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("seq", event.seq());
            item.put("timestamp", event.timestamp());
            item.put("name", event.name());
            item.put("data", event.data());
            if (event.subagentInvocationId() != null) {
                item.put("subagentInvocationId", event.subagentInvocationId());
            }
            events.add(item);
        }

        HashMap<String, Object> data = new HashMap<>();
        data.put("events", events);
        data.put("lastSeq", thread.getLastSseEventSeq());
        data.put("runStatus", thread.getRunStatus());
        data.putAll(runtimeSnapshot(thread, 0L));
        return data;
    }

    public Map<String, Object> resetThread(PuppetNodeSession session, String threadId, Integer requestedConfigId) {
        AiThread thread = session.getAiThread(threadId);
        AiThreadRecord persisted = findPersistedThread(session, threadId);
        if (thread == null) {
            thread = restorePersistedThread(session, threadId, persisted);
        }
        if (thread == null) {
            throw ApiException.notFound("线程不存在，threadId: " + threadId);
        }

        AiModelConfig config = resolveOptionalChannel(resolveConfigId(requestedConfigId, thread, persisted));
        Integer resolvedConfigId = config != null ? config.getId() : null;

        thread.stop();
        thread.clearSseEvents();
        thread.resetRuntimeStats();
        thread.setExecutionPolicy(AiExecutionPolicy.defaultPolicy());
        thread.resetTurnCount();
        thread.setAiConfigId(resolvedConfigId);
        puppetNodeAgents.remove(agentCacheKey(session, threadId));

        updateThreadMeta(session, thread);
        updateThreadConfig(session, thread, config);

        HashMap<String, Object> info = new HashMap<>();
        info.put("reconSummaryLoaded", session.hasReconSummary());
        return info;
    }

    public void switchChannel(PuppetNodeSession session, String threadId, Integer requestedConfigId) {
        AiThread thread = session.getAiThread(threadId);
        AiThreadRecord persisted = findPersistedThread(session, threadId);
        if (thread == null) {
            thread = restorePersistedThread(session, threadId, persisted);
        }
        if (thread == null) {
            throw ApiException.notFound("线程不存在，threadId: " + threadId);
        }

        AiModelConfig config = resolveOptionalChannel(resolveConfigId(requestedConfigId, thread, persisted));
        Integer configId = config != null ? config.getId() : null;
        thread.setAiConfigId(configId);
        puppetNodeAgents.remove(agentCacheKey(session, threadId));
        updateThreadConfig(session, thread, config);
    }

    public Map<String, Object> switchMode(PuppetNodeSession session, String threadId, String mode) {
        AiThread thread = session.getAiThread(threadId);
        AiThreadRecord persisted = findPersistedThread(session, threadId);
        if (thread == null) {
            thread = restorePersistedThread(session, threadId, persisted);
        }
        if (thread == null) {
            throw ApiException.notFound("线程不存在，threadId: " + threadId);
        }

        if (mode != null) {
            thread.setMode(mode);
        }
        conversationStore.updateMode(threadId, thread.getMode());

        HashMap<String, Object> info = new HashMap<>();
        info.put("threadId", threadId);
        info.put("mode", thread.getMode());
        return info;
    }

    public String withPersistedHistoryContext(PuppetNodeSession session, String threadId, String guardedMessage) {
        int total = conversationStore.countMessages(threadId);
        if (total <= 0) return guardedMessage;

        List<Map<String, Object>> messages = conversationStore.recentMessages(threadId, 16);

        StringBuilder history = new StringBuilder();
        int totalChars = 0;
        for (Map<String, Object> msg : messages) {
            String role = msg.get("role") != null ? String.valueOf(msg.get("role")) : "";
            if (!"user".equals(role) && !"assistant".equals(role)) continue;
            String content = msg.get("content") != null ? String.valueOf(msg.get("content")).trim() : "";
            if (content.isEmpty()) continue;
            content = truncate(content, 1200);
            String label = "user".equals(role) ? "用户" : "助手";
            String block = label + ":\n" + content + "\n\n";
            if (totalChars + block.length() > 8000) break;
            history.append(block);
            totalChars += block.length();
        }

        if (history.isEmpty()) return guardedMessage;
        return """
                【历史对话摘录】
                以下内容来自当前 AI 线程的数据库持久化记录。该线程刚恢复运行态，模型短期记忆为空；请把这些摘录作为延续上下文，避免重复询问已完成事项。

                %s
                【当前请求】
                %s
                """.formatted(history.toString().trim(), guardedMessage);
    }

    public void persistMessage(PuppetNodeSession session, String threadId, String role, String content) {
        persistMessage(session, threadId, role, content, null);
    }

    public void persistMessage(PuppetNodeSession session, String threadId, String role, String content,
                               Object attachments) {
        try {
            conversationStore.appendMessage(threadId, role, content, attachments);
        } catch (Exception e) {
            logger.warn("持久化消息失败, threadId={}: {}", threadId, e.getMessage());
        }
    }

    public void persistAssistantMessage(PuppetNodeSession session, String threadId,
                                        String content, List<AiSseEvent> eventLog) {
        persistAssistantMessage(session, threadId, content, eventLog, null, null);
    }

    public void persistAssistantMessage(PuppetNodeSession session, String threadId,
                                        String content, List<AiSseEvent> eventLog,
                                        Map<String, Object> review) {
        persistAssistantMessage(session, threadId, content, eventLog, review, null);
    }

    public void persistAssistantMessage(PuppetNodeSession session, String threadId,
                                        String content, List<AiSseEvent> eventLog,
                                        Map<String, Object> review,
                                        Object planSnapshot) {
        try {
            List<Object> nodes = new ArrayList<>();

            for (int i = 0; i < eventLog.size(); i++) {
                AiSseEvent event = eventLog.get(i);
                String name = event.name();
                String kind = kindOf(event.data());
                // 收集所有节点相关事件（thinking / text / plan / subtask）到统一列表，
                // 排除 node(kind=tool) 起始事件，只保留 patch(kind=tool) 完成事件，
                // 按 eventLog 位置注入 seq，确保历史恢复时时间线顺序与直播一致。
                if ("thinking".equals(name)
                        || ("node".equals(name) && ("thinking".equals(kind)
                                || "text".equals(kind)
                                || "plan".equals(kind) || "subtask".equals(kind)))
                        || ("patch".equals(name) && "tool".equals(kind))) {
                    long seq = event.seq() > 0 ? event.seq() : (i + 1L);
                    nodes.add(withEventSeq(event, seq));
                }
            }

            conversationStore.appendMessage(threadId, "assistant", content,
                    nodes, review, planSnapshot);
        } catch (Exception e) {
            logger.warn("持久化 assistant 消息失败, threadId={}: {}", threadId, e.getMessage());
        }
    }

    /**
     * 将事件 payload 转换为 Map 并注入 {@code seq}，使历史记录能复原与直播一致的事件顺序。
     * 失败时回退到原始 payload，保证持久化流程不被破坏。
     */
    private static Object withEventSeq(AiSseEvent event, long seq) {
        Object payload = event.data();
        if (payload == null) return null;
        try {
            String json = JSON.toJSONString(payload);
            @SuppressWarnings("unchecked")
            Map<String, Object> map = JSON.parseObject(json, Map.class);
            if (map == null) return payload;
            // payload 自带的 seq 优先（极少见，仅在测试中），否则注入持久化时统一计算的 seq
            map.putIfAbsent("seq", seq);
            return map;
        } catch (Exception e) {
            return payload;
        }
    }

    private Map<String, Object> buildTurnReview(String output, List<AiSseEvent> eventLog, long durationMs) {
        LinkedHashMap<String, Object> review = new LinkedHashMap<>();
        int toolCount = 0;
        int successCount = 0;
        int failureCount = 0;
        List<String> tools = new ArrayList<>();

        for (AiSseEvent event : eventLog) {
            if (!("patch".equals(event.name()) && "tool".equals(kindOf(event.data())))) continue;
            toolCount++;
            Object data = event.data();
            if (data instanceof Map<?, ?> map) {
                Object success = map.get("success");
                if (Boolean.FALSE.equals(success)) {
                    failureCount++;
                } else {
                    successCount++;
                }
                Object toolName = map.get("toolName");
                if (toolName instanceof String name && !name.isBlank() && !tools.contains(name)) {
                    tools.add(name);
                }
            } else {
                successCount++;
            }
        }

        review.put("durationMs", Math.max(0L, durationMs));
        review.put("toolCount", toolCount);
        review.put("successCount", successCount);
        review.put("failureCount", failureCount);
        review.put("tools", tools);
        review.put("conclusionPreview", truncate(output != null ? output.trim() : "", 500));
        review.put("createdAt", System.currentTimeMillis());
        return review;
    }

    public void updateThreadMeta(PuppetNodeSession session, AiThread thread) {
        try {
            conversationStore.updateRuntime(session.getSessionId(), thread);
        } catch (Exception e) {
            logger.warn("更新线程运行状态失败, threadId={}: {}", thread.getThreadId(), e.getMessage());
        }
    }

    private AiThread restorePersistedThread(PuppetNodeSession session, String threadId, AiThreadRecord record) {
        if (session == null || threadId == null || threadId.isBlank()) return null;
        String puppetId = PuppetNodeSessionWorkDirUtil.resolvePuppetId(session);
        String userId = session.getCreateByUser();
        if (puppetId == null || record == null) return null;
        if (!puppetId.equals(record.getPuppetId())) return null;
        if (record.getUserId() != null && !record.getUserId().equals(userId)) return null;

        String title = record.getTitle() != null && !record.getTitle().isBlank()
                ? record.getTitle() : "历史对话";
        AiThread thread = session.restoreAiThread(threadId, title,
                record.getCreatedAt() != null ? record.getCreatedAt() : 0L,
                record.getLastActiveAt() != null ? record.getLastActiveAt() : 0L);
        thread.setAiConfigId(record.getConfigId());
        thread.setMode(record.getMode());
        thread.setParentThreadId(record.getParentThreadId());
        restoreLatestPlan(thread, threadId);
        logger.debug("已从数据库恢复 AI 线程, sessionId={}, threadId={}, messages={}",
                session.getSessionId(), threadId, safeMessageCount(record.getMessageCount()));
        return thread;
    }

    /**
     * 从最近的助手消息中读取 plan 快照并恢复到线程的 planHistory，
     * 让重启后用户预批准过的步骤继续生效，不需要重新派发计划。
     *
     * <p>幂等性：若线程已有 currentPlan（例如并发恢复或同会话二次激活），直接返回，不重复 append。
     *
     * <p>陈旧性过滤：只还原 {@code PLANNING} / {@code IN_PROGRESS} 状态的计划。
     * 已 {@code COMPLETED} / {@code FAILED} 的计划如果还原成 currentPlan，
     * 会让 AI 误以为有"已完成的当前任务"，触发错误的 updatePlanStep/completePlan 调用。
     */
    private void restoreLatestPlan(AiThread thread, String threadId) {
        if (thread.getCurrentPlan() != null) return; // 幂等：已恢复过或本会话已新建
        try {
            String planJson = conversationStore.findLatestPlanJson(threadId);
            if (planJson == null) return;
            AiPlan plan = JSON.parseObject(planJson, AiPlan.class);
            if (plan == null) return;
            AiPlanStatus status = plan.getStatus();
            if (status != AiPlanStatus.PLANNING && status != AiPlanStatus.IN_PROGRESS) {
                logger.debug("跳过线程 {} 的陈旧计划恢复（状态 {} 已终结）", threadId, status);
                return;
            }
            thread.addPlan(plan);
            logger.debug("已恢复线程 {} 的活跃计划快照（状态 {}，共 {} 步）",
                    threadId, status, plan.getSteps() == null ? 0 : plan.getSteps().size());
        } catch (Exception e) {
            logger.warn("恢复线程 {} 的计划快照失败：{}", threadId, e.getMessage());
        }
    }

    private AiThreadRecord findPersistedThread(PuppetNodeSession session, String threadId) {
        if (session == null || threadId == null || threadId.isBlank()) return null;
        return conversationStore.findThread(threadId);
    }

    private Integer resolveConfigId(Integer requestedConfigId, AiThread thread, AiThreadRecord record) {
        if (requestedConfigId != null) return requestedConfigId;
        if (thread != null && thread.getAiConfigId() != null) return thread.getAiConfigId();
        return record != null ? record.getConfigId() : null;
    }

    private boolean hasThreadCheckpoint(PuppetNodeSession session, String threadId) {
        String puppetId = PuppetNodeSessionWorkDirUtil.resolvePuppetId(session);
        return puppetId != null && PuppetNodeSessionWorkDirUtil.hasAiThreadCheckpoint(
                session.getCreateByUser(), puppetId, threadId);
    }

    private AiModelConfig resolveChannel(Integer configId) {
        try {
            AiModelConfig resolved = modelConfigService.resolve(configId);
            if (resolved == null) {
                if (configId != null) {
                    throw ApiException.notFound("AI 模型不存在或已删除，configId: " + configId);
                }
                throw ApiException.notFound("未配置激活的 AI 模型，请先在设置中添加并激活一条");
            }
            return resolved;
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw ApiException.notFound(e.getMessage());
        }
    }

    private AiModelConfig resolveOptionalChannel(Integer configId) {
        if (configId == null) return null;
        return resolveChannel(configId);
    }

    private CachedPuppetNodeAgent threadAgent(PuppetNodeSession session, AiThread thread) {
        return threadAgent(session, thread, null);
    }

    private CachedPuppetNodeAgent threadAgent(PuppetNodeSession session, AiThread thread, String reasoningEffort) {
        AiModelConfig requested = resolveChannel(thread != null ? thread.getAiConfigId() : null);
        if (thread != null && thread.getAiConfigId() == null) {
            thread.setAiConfigId(requested.getId());
        }
        AiModelFailoverService.ModelSelection selection = failoverService.selectForExecution(requested);
        AiModelConfig config = selection.effectiveConfig();
        String agentKey = agentCacheKey(session, thread != null ? thread.getThreadId() : null);
        String cacheKey = requested.getId() + "->" + config.getId() + ":"
                + dynamicModelProvider.plannedRuntimeCacheKey(config, reasoningEffort);
        CachedPuppetNodeAgent cached = puppetNodeAgents.get(agentKey);
        if (cached != null && cacheKey.equals(cached.cacheKey())) {
            return cached;
        }
        DynamicModelProvider.ModelRuntime runtime = dynamicModelProvider.buildRuntime(config, reasoningEffort);
        PuppetNodeAgent agent = aiAgentFactory.createPuppetNodeAgent(
                runtime.streamingModel(), runtime.chatModel(), runtime.supportsFunctionCalling(),
                modelConfigService.getContextWindowTokens(config));
        CachedPuppetNodeAgent created = new CachedPuppetNodeAgent(cacheKey, agent,
                DynamicModelProvider.runtimeSnapshotJson(config, runtime), config.getId(),
                selection.failover() ? selection.message() : null);
        puppetNodeAgents.put(agentKey, created);
        return created;
    }

    private static String agentCacheKey(PuppetNodeSession session, String threadId) {
        String sessionId = session != null ? session.getSessionId() : "";
        return sessionId + ":" + (threadId != null ? threadId : "");
    }

    private record CachedPuppetNodeAgent(String cacheKey, PuppetNodeAgent agent, String runtimeJson,
                                         Integer effectiveConfigId, String failoverMessage) {}

    private String validateConfigId(Integer configId) {
        // 新架构下不再做能力探测，仅校验存在性
        if (configId == null) return null;
        try {
            AiModelConfig config = modelConfigService.findById(configId);
            if (config != null) {
                return null;
            }
            return "AI 通道不存在或已删除，configId: " + configId + "，请切换 AI 通道后重试";
        } catch (Exception e) {
            return "AI 通道校验失败，configId: " + configId + "，请检查配置后重试";
        }
    }

    private Map<String, Object> threadToMap(AiThread thread, int messageCount) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("threadId", thread.getThreadId());
        item.put("title", thread.getTitle());
        item.put("createdAt", thread.getCreatedAt());
        item.put("lastActiveAt", thread.getLastActiveAt());
        item.put("messageCount", messageCount);
        item.put("configId", thread.getAiConfigId());
        item.put("runStatus", thread.getRunStatus());
        item.put("executing", thread.isExecuting());
        item.put("mode", thread.getMode());
        item.put("parentThreadId", thread.getParentThreadId());
        item.put("inMemory", true);
        return item;
    }

    private Map<String, Object> threadRecordToMap(AiThreadRecord record) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("threadId", record.getThreadId());
        item.put("title", record.getTitle());
        item.put("createdAt", record.getCreatedAt());
        item.put("lastActiveAt", record.getLastActiveAt());
        item.put("messageCount", safeMessageCount(record.getMessageCount()));
        item.put("configId", record.getConfigId());
        item.put("configName", record.getConfigName());
        item.put("configProtocol", record.getConfigProtocol());
        item.put("configModel", record.getConfigModel());
        item.put("runStatus", record.getRunStatus() != null ? record.getRunStatus() : AiThread.STATUS_IDLE);
        item.put("executing", false);
        item.put("mode", record.getMode() != null ? record.getMode() : AiThread.MODE_AUTO);
        item.put("parentThreadId", record.getParentThreadId());
        item.put("inMemory", false);
        return item;
    }

    private void updateThreadConfig(PuppetNodeSession session, AiThread thread, AiModelConfig config) {
        try {
            String puppetId = PuppetNodeSessionWorkDirUtil.resolvePuppetId(session);
            if (puppetId == null) return;
            conversationStore.updateConfig(thread.getThreadId(), config);
        } catch (Exception e) {
            logger.warn("更新线程通道配置失败, threadId={}: {}", thread.getThreadId(), e.getMessage());
        }
    }

    private static int safeMessageCount(Integer count) {
        return count != null ? count : 0;
    }

    /** 从 Map payload 安全提取 {@code kind} 字段。 */
    private static String kindOf(Object data) {
        if (data instanceof Map<?, ?> map) {
            Object kind = map.get("kind");
            if (kind instanceof String s) return s;
        }
        return null;
    }

    /** 统计 eventLog 中主 Agent 的工具调用次数。 */
    private static int countToolCallEvents(List<AiSseEvent> eventLog) {
        int count = 0;
        for (AiSseEvent event : eventLog) {
            if ("patch".equals(event.name()) && "tool".equals(kindOf(event.data()))) {
                count++;
            }
        }
        return count;
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() > max ? value.substring(0, max) + "\n...(已截断)" : value;
    }

    public record ThreadResolution(AiThread thread, boolean restoredFromPersistence,
                                   boolean hasPersistentCheckpoint, String errorMessage) {
    }
}
