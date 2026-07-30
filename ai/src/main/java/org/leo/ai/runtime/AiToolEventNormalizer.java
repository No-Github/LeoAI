package org.leo.ai.runtime;

import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.service.tool.BeforeToolExecution;
import dev.langchain4j.service.tool.ToolExecution;
import org.leo.ai.agent.AiToolContext;
import org.leo.ai.agent.AiToolErrorHandler;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/** 将 LangChain4j 工具回调转换为稳定的 AI 领域事件载荷。 */
final class AiToolEventNormalizer {

    private AiToolEventNormalizer() {
    }

    static Map<String, Object> started(BeforeToolExecution execution) {
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        data.put("kind", "tool");
        data.put("toolName", execution.request().name());
        data.put("toolCallId", execution.request().id());
        data.put("arguments", AiToolArgumentSanitizer.sanitize(
                execution.request().arguments()));
        data.put("success", null);
        data.put("status", "running");
        data.put("timestamp", now);
        data.put("startTime", now);
        data.put("endTime", null);
        injectPlanStepIndex(data);
        return data;
    }

    static Map<String, Object> partial(PartialToolCall partial) {
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        String id = partial.id();
        data.put("kind", "tool");
        data.put("toolName", partial.name());
        data.put("toolCallId", id == null || id.isBlank() ? "tool-draft-" + partial.index() : id);
        data.put("arguments", AiToolArgumentSanitizer.sanitize(
                partial.partialArguments()));
        data.put("success", null);
        data.put("status", "running");
        data.put("message", "正在生成工具调用");
        data.put("timestamp", now);
        data.put("startTime", now);
        data.put("endTime", null);
        return data;
    }

    static Map<String, Object> completed(ToolExecution execution) {
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        long startTime = toEpochMs(execution.startTime(), now);
        boolean failed =
                AiToolErrorHandler.isErrorResult(execution);
        data.put("kind", "tool");
        data.put("toolName", execution.request().name());
        data.put("toolCallId", execution.request().id());
        data.put("arguments", AiToolArgumentSanitizer.sanitize(
                execution.request().arguments()));
        data.put("resultPreview", truncate(execution.result(), 2000));
        data.put("success", !failed);
        data.put("status", failed ? "failed" : "completed");
        data.put("timestamp", startTime);
        data.put("startTime", startTime);
        data.put("endTime", toEpochMs(execution.finishTime(), now));
        injectPlanStepIndex(data);
        return data;
    }

    private static void injectPlanStepIndex(Map<String, Object> data) {
        int index = AiToolContext.getPlanStepIndex();
        if (index >= 0) {
            data.put("planStepIndex", index);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() > max ? value.substring(0, max) + "\n...(已截断)" : value;
    }

    private static long toEpochMs(LocalDateTime value, long fallback) {
        if (value == null) return fallback;
        return value.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
