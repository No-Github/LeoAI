package org.leo.web.service;

import org.leo.ai.audit.AiAuditLogStore;
import org.leo.ai.channel.AiModelConfigService;
import org.leo.ai.platform.PlatformAiState;
import org.leo.ai.runtime.AiTurnCoordinator;
import org.leo.ai.runtime.AiTurnCommand;
import org.leo.ai.runtime.AiTurnOrchestrator;
import org.leo.ai.runtime.AiTurnTrace;
import org.leo.ai.runtime.AiTurnTransaction;
import org.leo.ai.thread.AiConversationStoreService;
import org.leo.core.entity.AiChatAuditEntry;
import org.leo.core.entity.AiExecutionPolicy;
import org.leo.core.entity.AiModelConfig;
import org.leo.web.exception.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class PlatformAiTurnService {

    private static final Logger logger = LoggerFactory.getLogger(PlatformAiTurnService.class);
    private final AiModelConfigService modelConfigService;
    private final AiAuditLogStore auditLogStore;
    private final AiConversationStoreService conversationStore;
    private final PlatformAiAgentRegistry agentRegistry;
    private final AiSseTurnPresenter sseTurnPresenter;
    private final AiTurnCoordinator turnCoordinator;
    private final AiTurnOrchestrator turnOrchestrator;

    public PlatformAiTurnService(AiModelConfigService modelConfigService,
                                 AiAuditLogStore auditLogStore,
                                 AiConversationStoreService conversationStore,
                                 PlatformAiAgentRegistry agentRegistry,
                                 AiSseTurnPresenter sseTurnPresenter,
                                 AiTurnCoordinator turnCoordinator,
                                 AiTurnOrchestrator turnOrchestrator) {
        this.modelConfigService = modelConfigService;
        this.auditLogStore = auditLogStore;
        this.conversationStore = conversationStore;
        this.agentRegistry = agentRegistry;
        this.sseTurnPresenter = sseTurnPresenter;
        this.turnCoordinator = turnCoordinator;
        this.turnOrchestrator = turnOrchestrator;
    }

    public AiChatAuditEntry appendChatAudit(AiExecutionPolicy policy, String message) {
        AiChatAuditEntry audit = AiChatAuditEntry.platform(
                policy.getUserId(),
                policy.getUserName(),
                policy.getPrivilege(),
                message,
                false);
        auditLogStore.append(audit);
        return audit;
    }

    public boolean tryClaimExecution(PlatformAiState state) {
        return turnCoordinator.tryClaim(state);
    }

    public void releaseExecutionClaim(PlatformAiState state) {
        turnCoordinator.releaseClaim(state);
    }

    public void executeChat(PlatformAiState state,
                            String sessionId,
                            String userMessage,
                            String guardedMessage,
                            AiChatAuditEntry audit,
                            SseEmitter emitter,
                            long startMs) {
        executeChat(state, sessionId, userMessage, guardedMessage, audit, emitter, startMs, null, null);
    }

    public void executeChat(PlatformAiState state,
                            String sessionId,
                            String userMessage,
                            String guardedMessage,
                            AiChatAuditEntry audit,
                            SseEmitter emitter,
                            long startMs,
                            String reasoningEffort) {
        executeChat(state, sessionId, userMessage, guardedMessage, audit, emitter, startMs,
                reasoningEffort, null);
    }

    public void executeChat(PlatformAiState state,
                            String sessionId,
                            String userMessage,
                            String guardedMessage,
                            AiChatAuditEntry audit,
                            SseEmitter emitter,
                            long startMs,
                            String reasoningEffort,
                            Object attachments) {
        AiTurnCoordinator.Execution turn = turnCoordinator.attach(state);
        String memoryId = state.getStateId();
        AiTurnTrace trace = AiTurnTrace.start(
                "platform", state.getStateId(), startMs);
        AiSseTurnPresenter.Session presentation = sseTurnPresenter.open(
                new AiSseTurnPresenter.Context(
                        "Platform AI", state, turn, emitter, audit, startMs,
                        trace,
                        () -> conversationStore.updateRuntime(
                                sessionId, state.getStateId(),
                                state.getLastActiveAt(), state.getRunStatus()),
                        null,
                        state::touchLastActiveAt,
                        () -> logger.info(
                                "[Thinking] 开始接收思考内容, stateId={}",
                                state.getStateId())));
        if (presentation == null) return;

        try {
            if (turn.isCancellationRequested()) {
                throw new InterruptedException("已停止");
            }
            state.touchLastActiveAt();
            String messageForAgent = guardedMessage;
            PlatformAiAgentRegistry.Runtime agentRuntime = threadAgent(state, reasoningEffort);
            trace.checkpoint(AiTurnTrace.Checkpoint.AGENT_RESOLVED);
            presentation.emitWarning(agentRuntime.failoverMessage());
            AiConversationStoreService.PersistedTurn persistedTurn =
                    conversationStore.beginTurn(
                    state.getStateId(), agentRuntime.effectiveConfigId(), messageForAgent,
                    userMessage, attachments, startMs, agentRuntime.runtimeJson(),
                    trace);
            turnOrchestrator.execute(
                    new AiTurnOrchestrator.Request(
                            new AiTurnCommand(
                                    state.getStateId(), memoryId, turn,
                                    () -> agentRuntime.agent().chat(
                                            memoryId, messageForAgent)),
                            new AiTurnTransaction.Context(
                                    persistedTurn, agentRuntime.effectiveConfigId(),
                                    agentRuntime.agent(), memoryId, audit, startMs,
                                    trace),
                            presentation.eventLog(),
                            state::getCurrentPlan,
                            state.getRuntimeStats()),
                    presentation);

        } catch (Exception e) {
            presentation.finishPreparationFailure(e);
        }
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
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw ApiException.notFound(e.getMessage());
        }
    }

    private PlatformAiAgentRegistry.Runtime threadAgent(
            PlatformAiState state, String reasoningEffort) {
        AiModelConfig requested = resolveChannel(state != null ? state.getAiConfigId() : null);
        if (state != null && state.getAiConfigId() == null) {
            state.setAiConfigId(requested.getId());
        }
        return agentRegistry.resolve(state, requested, reasoningEffort);
    }

}
