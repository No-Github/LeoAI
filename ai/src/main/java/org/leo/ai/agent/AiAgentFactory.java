package org.leo.ai.agent;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import org.leo.ai.service.AutoReconAppendService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ExecutorService;

import static org.leo.ai.agent.AiToolAuthorizationPolicy.AgentScope.PLATFORM;
import static org.leo.ai.agent.AiToolAuthorizationPolicy.AgentScope.PUPPET_NODE;

/**
 * Agent 构建工厂。
 *
 * <p>服务层按线程/通道构建运行时 Agent 时复用同一套工具、memory、system prompt
 * 和 LangChain4j 原生 AiServices 配置，避免通过全局代理模型临时切换通道。
 */
@Component
public class AiAgentFactory {

    private final ChatMemoryProvider memoryProvider;
    private final AiChatMemoryProviderFactory memoryProviderFactory;
    private final PuppetNodeSystemPromptProvider puppetNodeSystemPromptProvider;
    private final PlatformSystemPromptProvider platformSystemPromptProvider;
    private final PuppetNodeToolBundle puppetNodeToolBundle;
    private final PlatformToolBundle platformToolBundle;
    private final AutoReconAppendService autoReconAppendService;
    private final AiToolErrorHandler toolErrorHandler;
    private final AiToolAuthorizationPolicy toolAuthorizationPolicy;
    private final ExecutorService puppetNodeToolExecutor;
    private final ExecutorService platformToolExecutor;

    public AiAgentFactory(ChatMemoryProvider memoryProvider,
                          AiChatMemoryProviderFactory memoryProviderFactory,
                          PuppetNodeSystemPromptProvider puppetNodeSystemPromptProvider,
                          PlatformSystemPromptProvider platformSystemPromptProvider,
                          PuppetNodeToolBundle puppetNodeToolBundle,
                          PlatformToolBundle platformToolBundle,
                          AutoReconAppendService autoReconAppendService,
                          AiToolErrorHandler toolErrorHandler,
                          AiToolAuthorizationPolicy toolAuthorizationPolicy,
                          @Qualifier("puppetNodeAiToolExecutor")
                          ExecutorService puppetNodeToolExecutor,
                          @Qualifier("platformAiToolExecutor")
                          ExecutorService platformToolExecutor) {
        this.memoryProvider = memoryProvider;
        this.memoryProviderFactory = memoryProviderFactory;
        this.puppetNodeSystemPromptProvider = puppetNodeSystemPromptProvider;
        this.platformSystemPromptProvider = platformSystemPromptProvider;
        this.puppetNodeToolBundle = puppetNodeToolBundle;
        this.platformToolBundle = platformToolBundle;
        this.autoReconAppendService = autoReconAppendService;
        this.toolErrorHandler = toolErrorHandler;
        this.toolAuthorizationPolicy = toolAuthorizationPolicy;
        this.puppetNodeToolExecutor = puppetNodeToolExecutor;
        this.platformToolExecutor = platformToolExecutor;
    }

    public PuppetNodeAgent createPuppetNodeAgent(StreamingChatModel streamingModel, ChatModel chatModel) {
        return createPuppetNodeAgent(streamingModel, chatModel, true);
    }

    public PuppetNodeAgent createPuppetNodeAgent(StreamingChatModel streamingModel,
                                                 ChatModel chatModel,
                                                 boolean enableTools) {
        return createPuppetNodeAgent(streamingModel, chatModel, enableTools, memoryProvider);
    }

    public PuppetNodeAgent createPuppetNodeAgent(StreamingChatModel streamingModel,
                                                 ChatModel chatModel,
                                                 boolean enableTools,
                                                 int modelContextWindowTokens) {
        return createPuppetNodeAgent(streamingModel, chatModel, enableTools,
                memoryProviderFactory.createPuppetProvider(modelContextWindowTokens));
    }

    private PuppetNodeAgent createPuppetNodeAgent(StreamingChatModel streamingModel,
                                                  ChatModel chatModel,
                                                  boolean enableTools,
                                                  ChatMemoryProvider selectedMemoryProvider) {
        var builder = AiServices.builder(PuppetNodeAgent.class)
                .streamingChatModel(streamingModel)
                .chatModel(chatModel)
                .chatMemoryProvider(selectedMemoryProvider)
                .systemMessageProvider(puppetNodeSystemPromptProvider::getSystemMessage)
                .executeToolsConcurrently(puppetNodeToolExecutor)
                .toolArgumentsErrorHandler(
                        toolErrorHandler::handleArguments)
                .toolExecutionErrorHandler(
                        toolErrorHandler::handleExecution)
                .hallucinatedToolNameStrategy(
                        toolErrorHandler::handleUnknownTool)
                .beforeToolExecution(execution -> {
                    if (execution != null && execution.invocationContext() != null) {
                        toolAuthorizationPolicy.bindContext(
                                PUPPET_NODE,
                                execution.invocationContext().chatMemoryId());
                    }
                    checkPuppetPlanTimeouts();
                    autoAssociatePlanStep();
                })
                .afterToolExecution(execution -> {
                    try {
                        triggerAutoReconAppend(execution);
                        autoAppendToolResultToPlanStep(execution);
                        if (execution != null
                                && !AiToolErrorHandler.isErrorResult(execution)) {
                            toolErrorHandler.recordSuccess(
                                    execution.invocationContext().chatMemoryId(),
                                    execution.request().name());
                        }
                    } finally {
                        AiToolContext.clear();
                    }
                });
        if (enableTools) {
            builder.toolProvider(toolAuthorizationPolicy.toolProvider(
                    PUPPET_NODE, puppetNodeToolBundle.tools().toArray()));
        }
        return builder.build();
    }

    public PlatformAgent createPlatformAgent(StreamingChatModel streamingModel) {
        return createPlatformAgent(streamingModel, true);
    }

    public PlatformAgent createPlatformAgent(StreamingChatModel streamingModel, boolean enableTools) {
        return createPlatformAgent(streamingModel, enableTools, memoryProvider);
    }

    public PlatformAgent createPlatformAgent(StreamingChatModel streamingModel,
                                             boolean enableTools,
                                             int modelContextWindowTokens) {
        return createPlatformAgent(streamingModel, enableTools, modelContextWindowTokens, null);
    }

    /**
     * 创建平台 Agent，并按运行入口追加可选工具。
     *
     * <p>桥接 Puppet AI 的工具位于 web 模块，不能反向成为 ai 模块的固定依赖，
     * 因此由 {@code PlatformAiService} 在构建线程运行时时注入。
     */
    public PlatformAgent createPlatformAgent(StreamingChatModel streamingModel,
                                             boolean enableTools,
                                             int modelContextWindowTokens,
                                             Object additionalTools) {
        return createPlatformAgent(streamingModel, enableTools,
                memoryProviderFactory.createPlatformProvider(modelContextWindowTokens), additionalTools);
    }

    private PlatformAgent createPlatformAgent(StreamingChatModel streamingModel,
                                              boolean enableTools,
                                              ChatMemoryProvider selectedMemoryProvider) {
        return createPlatformAgent(streamingModel, enableTools, selectedMemoryProvider, null);
    }

    private PlatformAgent createPlatformAgent(StreamingChatModel streamingModel,
                                              boolean enableTools,
                                              ChatMemoryProvider selectedMemoryProvider,
                                              Object additionalTools) {
        var builder = AiServices.builder(PlatformAgent.class)
                .streamingChatModel(streamingModel)
                .chatMemoryProvider(selectedMemoryProvider)
                .systemMessageProvider(platformSystemPromptProvider::getSystemMessage)
                .executeToolsConcurrently(platformToolExecutor)
                .toolArgumentsErrorHandler(
                        toolErrorHandler::handleArguments)
                .toolExecutionErrorHandler(
                        toolErrorHandler::handleExecution)
                .hallucinatedToolNameStrategy(
                        toolErrorHandler::handleUnknownTool)
                .beforeToolExecution(execution -> {
                    if (execution != null && execution.invocationContext() != null) {
                        toolAuthorizationPolicy.bindContext(
                                PLATFORM,
                                execution.invocationContext().chatMemoryId());
                    }
                    checkPlatformPlanTimeouts();
                })
                .afterToolExecution(execution -> {
                    try {
                        if (execution != null
                                && !AiToolErrorHandler.isErrorResult(execution)) {
                            toolErrorHandler.recordSuccess(
                                    execution.invocationContext().chatMemoryId(),
                                    execution.request().name());
                        }
                    } finally {
                        AiToolContext.clear();
                    }
                });
        if (enableTools) {
            builder.toolProvider(toolAuthorizationPolicy.toolProvider(
                    PLATFORM, platformToolBundle.toolsWith(additionalTools).toArray()));
        }
        return builder.build();
    }

    private static final java.util.Set<String> AUTO_RECON_APPEND_SKIPPED_TOOLS = java.util.Set.of(
            "manage_recon_summary",
            "createPlan", "updatePlan", "getPlan", "deletePlan",
            "activate_skill", "request_user_input"
    );

    private void triggerAutoReconAppend(dev.langchain4j.service.tool.ToolExecution execution) {
        if (execution == null
                || AiToolErrorHandler.isErrorResult(execution)) return;
        String toolName = execution.request() != null ? execution.request().name() : null;
        if (toolName == null || AUTO_RECON_APPEND_SKIPPED_TOOLS.contains(toolName)) return;

        String sessionId = AiToolContext.getSessionId();
        if (sessionId == null || sessionId.isBlank()) return;

        String result = resultForInternalUse(execution);
        if (result == null || result.isBlank()) return;

        try {
            autoReconAppendService.analyzeAndAppend(sessionId, toolName, result);
        } catch (Throwable t) {
            org.slf4j.LoggerFactory.getLogger(AiAgentFactory.class)
                    .debug("AutoReconAppend 触发失败 tool={} sessionId={}: {}",
                            toolName, sessionId, t.getMessage());
        }
    }

    private static final java.util.Set<String> PLAN_TOOLS = java.util.Set.of(
            "createPlan", "updatePlanStep", "completePlan");

    private static void checkPuppetPlanTimeouts() {
        try {
            var thread = resolveCurrentPuppetThread();
            if (thread == null || thread.getCurrentPlan() == null) return;
            var plan = thread.getCurrentPlan();
            if (plan.checkStepTimeouts() <= 0) return;
            java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("kind", "plan");
            payload.put("planId", plan.getPlanId());
            payload.put("title", plan.getTitle());
            payload.put("goal", plan.getGoal());
            payload.put("status", plan.getStatus().name());
            payload.put("steps", plan.getSteps());
            thread.offerSseEvent("patch", payload);
        } catch (Exception ignored) {
            // 超时检查不应阻断工具调用
        }
    }

    private static void checkPlatformPlanTimeouts() {
        try {
            String stateId = AiToolContext.getSessionId();
            if (stateId == null || stateId.isBlank()) return;
            var state = org.leo.ai.platform.PlatformAiStateStore.get(stateId);
            if (state == null || state.getCurrentPlan() == null) return;
            if (state.getCurrentPlan().checkStepTimeouts() > 0) {
                state.notifyPlanUpdated();
            }
        } catch (Exception ignored) {
            // 超时检查不应阻断工具调用
        }
    }

    private static void autoAssociatePlanStep() {
        try {
            var thread = resolveCurrentPuppetThread();
            if (thread == null) return;

            var plan = thread.getCurrentPlan();
            if (plan == null) return;
            int stepIndex = findInProgressStepIndex(plan);
            if (stepIndex >= 0) AiToolContext.setPlanStepIndex(stepIndex);
        } catch (Exception ignored) {
            // best-effort，失败不影响工具执行
        }
    }

    private static org.leo.core.session.AiThread resolveCurrentPuppetThread() {
        String sessionId = AiToolContext.getSessionId();
        String threadId = AiToolContext.getThreadId();
        if (sessionId == null || sessionId.isBlank()) return null;
        var session = org.leo.core.session.PuppetNodeSessionContainer.getSession(sessionId);
        if (session == null) return null;
        return threadId != null && !threadId.isBlank()
                ? session.getAiThread(threadId) : session.getActiveThread();
    }

    static int findInProgressStepIndex(org.leo.core.entity.AiPlan plan) {
        if (plan == null || plan.getSteps() == null) return -1;
        for (var step : plan.getSteps()) {
            if (step.getStatus() == org.leo.core.entity.AiStepStatus.IN_PROGRESS) {
                return step.getIndex();
            }
        }
        return -1;
    }

    private static void autoAppendToolResultToPlanStep(
            dev.langchain4j.service.tool.ToolExecution execution) {
        int stepIndex = AiToolContext.getPlanStepIndex();
        if (stepIndex < 0) return;
        if (execution == null) return;

        String toolName = execution.request() != null ? execution.request().name() : null;
        if (toolName == null || PLAN_TOOLS.contains(toolName)) return;

        try {
            String sessionId = AiToolContext.getSessionId();
            String threadId = AiToolContext.getThreadId();
            if (sessionId == null || sessionId.isBlank()) return;

            var session = org.leo.core.session.PuppetNodeSessionContainer.getSession(sessionId);
            if (session == null) return;

            var thread = (threadId != null) ? session.getAiThread(threadId) : session.getActiveThread();
            if (thread == null) return;

            var plan = thread.getCurrentPlan();
            if (plan == null) return;

            var steps = plan.getSteps();
            if (steps == null) return;

            for (var step : steps) {
                if (step.getIndex() == stepIndex) {
                    String summary = buildToolResultSummary(toolName, execution);
                    if (step.getResult() != null && !step.getResult().isBlank()) {
                        step.setResult(step.getResult() + " | " + summary);
                    } else {
                        step.setResult(summary);
                    }
                    thread.offerSseEvent("patch", buildPlanStepPatch(plan, step, toolName));
                    break;
                }
            }
        } catch (Exception ignored) {
            // best-effort
        }
    }

    private static String buildToolResultSummary(String toolName,
                                                  dev.langchain4j.service.tool.ToolExecution execution) {
        StringBuilder sb = new StringBuilder(toolName);
        if (AiToolErrorHandler.isErrorResult(execution)) {
            sb.append(" 失败");
            String err = execution.result();
            if (err != null && !err.isBlank()) {
                String shortErr = err.length() > 80 ? err.substring(0, 80) + "…" : err;
                sb.append("（").append(shortErr).append("）");
            }
        } else {
            sb.append(" 完成");
            String result = resultForInternalUse(execution);
            if (result != null && !result.isBlank()) {
                String firstLine = result.lines().findFirst().orElse("");
                String shortResult = firstLine.length() > 80 ? firstLine.substring(0, 80) + "…" : firstLine;
                sb.append(" → ").append(shortResult);
            }
        }
        return sb.toString();
    }

    private static String resultForInternalUse(
            dev.langchain4j.service.tool.ToolExecution execution) {
        if (execution == null) return null;
        Object raw = execution.resultObject();
        if (raw instanceof String text) return text;
        if (raw != null) {
            try {
                return org.leo.core.util.json.JsonUtil.toJsonString(raw);
            } catch (RuntimeException ignored) {
                return String.valueOf(raw);
            }
        }
        return execution.result();
    }

    private static Map<String, Object> buildPlanStepPatch(
            org.leo.core.entity.AiPlan plan, org.leo.core.entity.AiPlanStep step, String toolName) {
        java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("kind", "plan");
        payload.put("planId", plan.getPlanId());
        payload.put("stepIndex", step.getIndex());
        payload.put("status", step.getStatus().name());
        payload.put("result", step.getResult());
        payload.put("toolName", toolName);
        payload.put("timestamp", System.currentTimeMillis());
        return payload;
    }
}
