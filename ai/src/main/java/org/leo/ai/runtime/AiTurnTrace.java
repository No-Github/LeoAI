package org.leo.ai.runtime;

import org.leo.core.util.json.JsonUtil;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次 AI Turn 从接入层准备到最终呈现的统一可观测上下文。
 *
 * <p>{@code traceId} 在持久化 Run 产生前创建，因此也能关联通道解析、SSE 启动等
 * 准备阶段失败；{@code runId}/{@code turnId} 在数据库记录创建后再绑定。
 */
public final class AiTurnTrace {

    private final String traceId;
    private final String source;
    private final String conversationId;
    private final long startedAt;
    private final LinkedHashMap<String, Long> checkpoints = new LinkedHashMap<>();
    private final AtomicBoolean recorded = new AtomicBoolean(false);

    private String turnId;
    private String runId;
    private Long finishedAt;
    private String outcome;
    private String errorCategory;
    private String errorMessage;

    private AiTurnTrace(String traceId, String source,
                        String conversationId, long startedAt) {
        this.traceId = requireText(traceId, "traceId");
        this.source = requireText(source, "source");
        this.conversationId = requireText(conversationId, "conversationId");
        this.startedAt = startedAt > 0 ? startedAt : System.currentTimeMillis();
        checkpoint(Checkpoint.CREATED);
    }

    public static AiTurnTrace start(String source, String conversationId,
                                    long startedAt) {
        return new AiTurnTrace(
                UUID.randomUUID().toString(), source, conversationId, startedAt);
    }

    static AiTurnTrace testTrace(String traceId, String source,
                                 String conversationId, long startedAt) {
        return new AiTurnTrace(traceId, source, conversationId, startedAt);
    }

    public synchronized void bind(String turnId, String runId) {
        this.turnId = requireText(turnId, "turnId");
        this.runId = requireText(runId, "runId");
        checkpoint(Checkpoint.TURN_PERSISTED);
    }

    public synchronized void checkpoint(Checkpoint checkpoint) {
        if (checkpoint == null) return;
        checkpoints.putIfAbsent(
                checkpoint.value(), Math.max(0L, System.currentTimeMillis() - startedAt));
    }

    public synchronized void finish(AiTurnOutcome outcome,
                                    String errorCategory,
                                    String errorMessage) {
        if (finishedAt != null) return;
        checkpoint(Checkpoint.TERMINAL);
        this.finishedAt = System.currentTimeMillis();
        this.outcome = outcome != null ? outcome.name().toLowerCase() : "failed";
        this.errorCategory = blankToNull(errorCategory);
        this.errorMessage = blankToNull(errorMessage);
    }

    public synchronized Map<String, Object> eventPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("traceId", traceId);
        payload.put("source", source);
        payload.put("startedAt", startedAt);
        if (turnId != null) payload.put("turnId", turnId);
        if (runId != null) payload.put("runId", runId);
        return payload;
    }

    public synchronized Map<String, Object> snapshot() {
        Map<String, Object> result = eventPayload();
        result.put("conversationId", conversationId);
        result.put("finishedAt", finishedAt);
        result.put("durationMs", durationMillis());
        result.put("outcome", outcome);
        if (errorCategory != null) result.put("errorCategory", errorCategory);
        if (errorMessage != null) result.put("errorMessage", errorMessage);
        result.put("checkpoints", new LinkedHashMap<>(checkpoints));
        result.put("phaseDurations", phaseDurations());
        return result;
    }

    public synchronized String toJson() {
        return JsonUtil.toJsonString(snapshot());
    }

    public String traceId() {
        return traceId;
    }

    public String source() {
        return source;
    }

    public String conversationId() {
        return conversationId;
    }

    public long startedAt() {
        return startedAt;
    }

    public synchronized String turnId() {
        return turnId;
    }

    public synchronized String runId() {
        return runId;
    }

    public synchronized String outcome() {
        return outcome;
    }

    public synchronized String errorCategory() {
        return errorCategory;
    }

    public synchronized long durationMillis() {
        long end = finishedAt != null ? finishedAt : System.currentTimeMillis();
        return Math.max(0L, end - startedAt);
    }

    boolean markRecorded() {
        return recorded.compareAndSet(false, true);
    }

    private Map<String, Long> phaseDurations() {
        Map<String, Long> phases = new LinkedHashMap<>();
        String previousName = null;
        long previousOffset = 0L;
        for (Map.Entry<String, Long> entry : checkpoints.entrySet()) {
            if (previousName != null) {
                phases.put(
                        previousName + "->" + entry.getKey(),
                        Math.max(0L, entry.getValue() - previousOffset));
            }
            previousName = entry.getKey();
            previousOffset = entry.getValue();
        }
        return phases;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public enum Checkpoint {
        CREATED("created"),
        TRANSPORT_READY("transport_ready"),
        AGENT_RESOLVED("agent_resolved"),
        TURN_PERSISTED("turn_persisted"),
        PREPARATION_FAILED("preparation_failed"),
        ORCHESTRATION_STARTED("orchestration_started"),
        PRESENTATION_STARTED("presentation_started"),
        MODEL_STARTED("model_started"),
        FIRST_EVENT("first_event"),
        MODEL_COMPLETED("model_completed"),
        MODEL_FAILED("model_failed"),
        PERSISTENCE_STARTED("persistence_started"),
        PERSISTENCE_COMPLETED("persistence_completed"),
        PRESENTATION_COMPLETED("presentation_completed"),
        RECOVERY_STARTED("recovery_started"),
        RECOVERY_COMPLETED("recovery_completed"),
        TERMINAL("terminal");

        private final String value;

        Checkpoint(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }
}
