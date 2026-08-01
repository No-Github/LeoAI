package org.leo.ai.runtime;

import com.alibaba.fastjson.JSON;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.service.tool.BeforeToolExecution;
import dev.langchain4j.service.tool.ToolExecution;
import org.leo.ai.agent.AiToolContext;
import org.leo.ai.agent.AiToolErrorHandler;
import org.leo.ai.agent.AiToolOperation;

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
        data.put("operation", AiToolOperation.classify(
                execution.request().name()).name());
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
        data.put("operation", AiToolOperation.classify(
                execution.request().name()).name());
        data.put("arguments", AiToolArgumentSanitizer.sanitize(
                execution.request().arguments()));
        data.put("resultPreview", truncate(resultForDisplay(execution), 2000));
        data.put("success", !failed);
        data.put("status", failed ? "failed" : "completed");
        data.put("timestamp", startTime);
        data.put("startTime", startTime);
        data.put("endTime", toEpochMs(execution.finishTime(), now));
        data.put("durationMs", Math.max(0L,
                toEpochMs(execution.finishTime(), now) - startTime));
        injectProtocolMetadata(data, execution.result());
        injectPlanStepIndex(data);
        return data;
    }

    @SuppressWarnings("unchecked")
    private static void injectProtocolMetadata(Map<String, Object> data, String result) {
        if (result == null || result.isBlank() || result.charAt(0) != '{') return;
        try {
            Map<String, Object> envelope = JSON.parseObject(result, Map.class);
            if (envelope == null) return;
            copyIfPresent(envelope, data, "protocol");
            copyIfPresent(envelope, data, "code");
            copyIfPresent(envelope, data, "retryable");
            Object rawMetadata = envelope.get("metadata");
            if (rawMetadata instanceof Map<?, ?> metadata) {
                for (String key : java.util.List.of(
                        "operation", "timeoutMs", "truncated",
                        "originalChars", "deduplicated", "archiveId",
                        "recoverableBy", "attempt", "maxAttempts")) {
                    Object value = metadata.get(key);
                    if (value != null) data.put(key, value);
                }
                Object executionDuration = metadata.get("durationMs");
                if (executionDuration != null) {
                    data.put("executionDurationMs", executionDuration);
                }
            }
        } catch (RuntimeException ignored) {
            // 普通文本工具结果不参与协议元数据提取。
        }
    }

    private static void copyIfPresent(Map<String, Object> source,
                                      Map<String, Object> target,
                                      String key) {
        Object value = source.get(key);
        if (value != null) target.put(key, value);
    }

    private static String resultForDisplay(ToolExecution execution) {
        Object raw = execution.resultObject();
        if (raw instanceof String text) return text;
        if (raw != null) {
            try {
                return JSON.toJSONString(raw);
            } catch (RuntimeException ignored) {
                return String.valueOf(raw);
            }
        }
        return execution.result();
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
