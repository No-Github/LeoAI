package org.leo.web.service;

import org.leo.ai.channel.AiModelConfigService;
import org.leo.ai.runtime.AiTurnCommand;
import org.leo.ai.runtime.AiTurnCoordinator;
import org.leo.ai.runtime.AiTurnOrchestrator;
import org.leo.ai.runtime.AiTurnTrace;
import org.leo.ai.runtime.AiTurnTransaction;
import org.leo.ai.thread.AiConversationStoreService;
import org.leo.core.entity.AiChatAuditEntry;
import org.leo.core.entity.AiModelConfig;
import org.leo.core.entity.AiPlan;
import org.leo.core.entity.AiPlanStatus;
import org.leo.core.session.AiThread;
import org.leo.core.session.PuppetNodeSession;
import org.leo.web.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;

/** 执行 Puppet 节点 AI 的一个主对话 Turn。 */
@Service
public class PuppetNodeAiTurnService {

    private static final Logger logger = LoggerFactory.getLogger(PuppetNodeAiTurnService.class);
    private final AiModelConfigService modelConfigService;
    private final AiConversationStoreService conversationStore;
    private final PuppetNodeAiAgentRegistry agentRegistry;
    private final AiSseTurnPresenter sseTurnPresenter;
    private final AiTurnCoordinator turnCoordinator;
    private final AiTurnOrchestrator turnOrchestrator;

    public PuppetNodeAiTurnService(AiModelConfigService modelConfigService,
                                   AiConversationStoreService conversationStore,
                                   PuppetNodeAiAgentRegistry agentRegistry,
                                   AiSseTurnPresenter sseTurnPresenter,
                                   AiTurnCoordinator turnCoordinator,
                                   AiTurnOrchestrator turnOrchestrator) {
        this.modelConfigService = modelConfigService;
        this.conversationStore = conversationStore;
        this.agentRegistry = agentRegistry;
        this.sseTurnPresenter = sseTurnPresenter;
        this.turnCoordinator = turnCoordinator;
        this.turnOrchestrator = turnOrchestrator;
    }

    public boolean tryClaimExecution(AiThread thread) {
        return turnCoordinator.tryClaim(thread);
    }

    public void releaseExecutionClaim(AiThread thread) {
        turnCoordinator.releaseClaim(thread);
    }

    public void executeChat(PuppetNodeSession session,
                            AiThread thread,
                            String threadId,
                            String messageForAgent,
                            AiChatAuditEntry audit,
                            SseEmitter emitter,
                            long startMs,
                            String reasoningEffort,
                            String userContent,
                            Object attachments) {
        AiTurnCoordinator.Execution turn = turnCoordinator.attach(thread);
        String memoryId = session.getSessionId() + ":" + threadId;
        AiTurnTrace trace = AiTurnTrace.start(
                "puppet", threadId, startMs);
        AiSseTurnPresenter.Session presentation = sseTurnPresenter.open(
                new AiSseTurnPresenter.Context(
                        "Puppet AI", thread, turn, emitter, audit, startMs,
                        trace,
                        () -> conversationStore.updateRuntime(
                                session.getSessionId(), thread),
                        () -> emitCurrentPlanAtTurnStart(thread),
                        null,
                        () -> logger.info(
                                "[Thinking] 开始接收思考内容, memoryId={}",
                                memoryId)));
        if (presentation == null) return;

        try {
            if (turn.isCancellationRequested()) throw new InterruptedException("已停止");
            PuppetNodeAiAgentRegistry.Runtime agentRuntime =
                    resolveAgent(session, thread, reasoningEffort);
            trace.checkpoint(AiTurnTrace.Checkpoint.AGENT_RESOLVED);
            presentation.emitWarning(agentRuntime.failoverMessage());
            AiConversationStoreService.PersistedTurn persistedTurn =
                    conversationStore.beginTurn(
                    threadId, agentRuntime.effectiveConfigId(), messageForAgent,
                    userContent, attachments, startMs, agentRuntime.runtimeJson(),
                    trace);
            turnOrchestrator.execute(
                    new AiTurnOrchestrator.Request(
                            new AiTurnCommand(
                                    threadId, memoryId, turn,
                                    () -> agentRuntime.agent().chat(
                                            memoryId, messageForAgent)),
                            new AiTurnTransaction.Context(
                                    persistedTurn, agentRuntime.effectiveConfigId(),
                                    agentRuntime.agent(), memoryId, audit, startMs,
                                    trace),
                            presentation.eventLog(),
                            thread::getCurrentPlan,
                            thread.getRuntimeStats()),
                    presentation);
        } catch (Exception error) {
            presentation.finishPreparationFailure(error);
        }
    }

    private PuppetNodeAiAgentRegistry.Runtime resolveAgent(
            PuppetNodeSession session, AiThread thread, String reasoningEffort) {
        AiModelConfig requested = resolveChannel(thread != null ? thread.getAiConfigId() : null);
        if (thread != null && thread.getAiConfigId() == null) {
            thread.setAiConfigId(requested.getId());
        }
        return agentRegistry.resolve(session, thread, requested, reasoningEffort);
    }

    private AiModelConfig resolveChannel(Integer configId) {
        try {
            AiModelConfig resolved = modelConfigService.resolve(configId);
            if (resolved == null) {
                if (configId != null) {
                    throw ApiException.notFound(
                            "AI 模型不存在或已删除，configId: " + configId);
                }
                throw ApiException.notFound(
                        "未配置激活的 AI 模型，请先在设置中添加并激活一条");
            }
            return resolved;
        } catch (IllegalArgumentException | IllegalStateException error) {
            throw ApiException.notFound(error.getMessage());
        }
    }

    private void emitCurrentPlanAtTurnStart(AiThread thread) {
        AiPlan plan = thread.getCurrentPlan();
        if (plan == null || plan.getStatus() != AiPlanStatus.IN_PROGRESS) return;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "plan");
        payload.put("planId", plan.getPlanId());
        payload.put("title", plan.getTitle());
        payload.put("goal", plan.getGoal());
        payload.put("status", plan.getStatus().name());
        payload.put("steps", plan.getSteps());
        if (plan.getFinalSummary() != null) {
            payload.put("finalSummary", plan.getFinalSummary());
        }
        thread.offerSseEvent("node", payload);
    }
}
