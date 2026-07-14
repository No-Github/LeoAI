package org.leo.ai.tools.platform;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.leo.ai.agent.AiToolContext;
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
            default -> throw new IllegalArgumentException(
                    "无效的 action：" + action + "，可选值：start | complete | fail | skip");
        };
        if (!updated) {
            if ("start".equals(normalizedAction)
                    && plan.getSteps().stream().anyMatch(step -> step.getIndex() == stepIndex)) {
                throw new IllegalStateException("步骤 " + stepIndex + " 的依赖步骤尚未完成，无法启动");
            }
            throw new IllegalStateException("未找到指定步骤: " + stepIndex);
        }
        state.notifyPlanUpdated();
        return success(plan);
    }

    @Tool("结束当前平台计划，并记录最终结论。")
    public Map<String, Object> completePlan(@P("最终结论") String finalSummary) {
        PlatformAiState state = requireState();
        AiPlan plan = requirePlan(state);
        plan.complete(finalSummary);
        state.notifyPlanUpdated();
        return success(plan);
    }

    private PlatformAiState requireState() {
        String stateId = AiToolContext.requireSessionId();
        PlatformAiState state = PlatformAiStateStore.get(stateId);
        if (state == null) {
            throw new IllegalStateException("平台 AI 会话不存在，无法操作计划");
        }
        return state;
    }

    private static AiPlan requirePlan(PlatformAiState state) {
        AiPlan plan = state.getCurrentPlan();
        if (plan == null) {
            throw new IllegalStateException("当前没有可操作的计划");
        }
        return plan;
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
