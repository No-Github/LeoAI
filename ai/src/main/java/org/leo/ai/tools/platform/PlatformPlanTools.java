package org.leo.ai.tools.platform;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.leo.ai.agent.AiToolContext;
import org.leo.ai.agent.AiToolException;
import org.leo.ai.platform.PlatformAiState;
import org.leo.ai.platform.PlatformAiStateStore;
import org.leo.core.entity.AiPlan;
import org.leo.core.entity.AiPlanStep;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 平台 AI 的任务计划工具，计划状态绑定当前平台 AI stateId。 */
@Component
public class PlatformPlanTools {

    @Tool("""
            创建当前平台 AI 任务的执行计划。steps 中每个步骤可包含：
            description, toolHint, parallel, successCriteria, maxRetries, dependsOn。
            适用于多资源管理、跨 Puppet 委派或需要多个工具调用的任务。
            """)
    public Map<String, Object> createPlan(
            @P("计划标题") String title,
            @P("任务目标") String goal,
            @P("步骤列表（对象数组）") List<Map<String, Object>> steps,
            @P("步骤超时毫秒，0 表示不启用（可选，默认 0）") long stepTimeoutMs) {
        PlatformAiState state = requireState();
        AiPlan plan = new AiPlan(title, goal, normalizeSteps(steps));
        if (stepTimeoutMs > 0) {
            plan.setStepTimeoutMs(stepTimeoutMs);
        }
        state.setCurrentPlan(plan);
        return success(plan);
    }

    @Tool("""
            更新当前平台计划的指定步骤。
            action 取值：start、complete、fail、skip；stepIndex 从 0 开始。
            complete、fail、skip 时通过 resultText 记录真实结果或原因。
            """)
    public Map<String, Object> updatePlanStep(
            @P("步骤序号，从 0 开始") int stepIndex,
            @P("操作：start | complete | fail | skip") String action,
            @P("结果摘要或原因") String resultText) {
        PlatformAiState state = requireState();
        AiPlan plan = requirePlan(state);
        String normalizedAction = action == null ? "" : action.trim();
        String text = resultText == null || resultText.isBlank() ? null : resultText.trim();
        boolean updated = switch (normalizedAction) {
            case "start" -> plan.startStep(stepIndex);
            case "complete" -> plan.completeStep(stepIndex, text);
            case "fail" -> plan.failStep(stepIndex, text);
            case "skip" -> plan.skipStep(stepIndex, text);
            default -> throw AiToolException.modelCorrectable(
                    "INVALID_ACTION",
                    "无效的 action：" + action
                            + "，可选值：start | complete | fail | skip",
                    "将 action 改为 start、complete、fail 或 skip。");
        };
        if (!updated) {
            throw transitionError(plan, stepIndex, normalizedAction, text);
        }
        state.notifyPlanUpdated();
        return success(plan);
    }

    @Tool("结束当前平台计划，并记录最终结论。")
    public Map<String, Object> completePlan(@P("最终结论") String finalSummary) {
        PlatformAiState state = requireState();
        AiPlan plan = requirePlan(state);
        try {
            plan.complete(finalSummary);
        } catch (IllegalStateException error) {
            throw AiToolException.modelCorrectable(
                    "PLAN_STEPS_ACTIVE",
                    error.getMessage(),
                    "先将所有 PENDING/IN_PROGRESS 步骤完成、失败或跳过，再结束计划。");
        }
        state.notifyPlanUpdated();
        return success(plan);
    }

    private PlatformAiState requireState() {
        String stateId = AiToolContext.requireSessionId();
        PlatformAiState state = PlatformAiStateStore.get(stateId);
        if (state == null) {
            throw AiToolException.userActionRequired(
                    "SESSION_EXPIRED",
                    "平台 AI 会话不存在，无法操作计划。",
                    "不要重复调用；请向用户说明需要重新打开或创建 AI 会话。");
        }
        return state;
    }

    private static AiPlan requirePlan(PlatformAiState state) {
        AiPlan plan = state.getCurrentPlan();
        if (plan == null) {
            throw AiToolException.modelCorrectable(
                    "PLAN_NOT_FOUND",
                    "当前没有可操作的计划。",
                    "先调用 createPlan 创建计划，再操作计划步骤。");
        }
        return plan;
    }

    private static AiToolException transitionError(
            AiPlan plan, int stepIndex, String action, String text) {
        AiPlanStep step = plan.getStep(stepIndex);
        if (step == null) {
            return AiToolException.modelCorrectable(
                    "PLAN_STEP_NOT_FOUND",
                    "未找到指定步骤: " + stepIndex,
                    "检查当前计划中的步骤索引后重新调用。");
        }
        if ("start".equals(action)) {
            if (step.getStatus() == org.leo.core.entity.AiStepStatus.FAILED
                    && !step.canStart()) {
                return AiToolException.modelCorrectable(
                        "PLAN_RETRY_EXHAUSTED",
                        "步骤 " + stepIndex + " 已达到最大重试次数。",
                        "不要再次启动；请结束失败计划并在最终结论中说明原因。");
            }
            return AiToolException.modelCorrectable(
                    "PLAN_DEPENDENCY_INCOMPLETE",
                    "步骤 " + stepIndex + " 的依赖步骤尚未成功完成，无法启动。",
                    "dependsOn 中的步骤必须为 COMPLETED；失败或跳过不视为满足依赖。");
        }
        if ("complete".equals(action)
                && step.getSuccessCriteria() != null
                && !step.getSuccessCriteria().isBlank()
                && (text == null || text.isBlank())) {
            return AiToolException.modelCorrectable(
                    "PLAN_SUCCESS_EVIDENCE_REQUIRED",
                    "步骤 " + stepIndex + " 定义了成功标准，完成时必须提供结果摘要。",
                    "在 resultText 中写明满足 successCriteria 的实际证据。");
        }
        return AiToolException.modelCorrectable(
                "PLAN_INVALID_TRANSITION",
                "步骤 " + stepIndex + " 当前状态为 " + step.getStatus()
                        + "，不能执行 " + action + "。",
                "先按 PENDING → IN_PROGRESS → COMPLETED/FAILED 的顺序更新；skip 可用于放弃未完成步骤。");
    }

    private static Map<String, Object> success(AiPlan plan) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("plan", plan);
        return result;
    }

    private static List<AiPlanStep> normalizeSteps(List<Map<String, Object>> steps) {
        List<AiPlanStep> result = new ArrayList<>();
        if (steps == null) return result;
        for (int i = 0; i < steps.size(); i++) {
            Map<String, Object> raw = steps.get(i);
            if (raw == null) continue;
            String description = text(raw.get("description"));
            if (description.isBlank()) continue;
            result.add(new AiPlanStep(
                    number(raw.get("index"), i),
                    description,
                    text(raw.get("toolHint")),
                    bool(raw.get("parallel")),
                    text(raw.get("successCriteria")),
                    number(raw.get("maxRetries"), 1),
                    numberList(raw.get("dependsOn"))));
        }
        return result;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
    }

    private static int number(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static List<Integer> numberList(Object value) {
        List<Integer> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                try {
                    result.add(Integer.parseInt(String.valueOf(item)));
                } catch (Exception ignored) {
                    // 忽略无效依赖序号
                }
            }
        }
        return result;
    }
}
