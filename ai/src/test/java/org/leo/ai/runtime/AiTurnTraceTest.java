package org.leo.ai.runtime;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiTurnTraceTest {

    @Test
    void bindsPersistenceIdentityAndBuildsOrderedPhaseDurations() {
        AiTurnTrace trace = AiTurnTrace.testTrace(
                "trace-1", "platform", "thread-1",
                System.currentTimeMillis());

        trace.checkpoint(AiTurnTrace.Checkpoint.TRANSPORT_READY);
        trace.checkpoint(AiTurnTrace.Checkpoint.AGENT_RESOLVED);
        trace.bind("turn-1", "run-1");
        trace.checkpoint(AiTurnTrace.Checkpoint.MODEL_STARTED);
        trace.checkpoint(AiTurnTrace.Checkpoint.FIRST_EVENT);
        trace.finish(AiTurnOutcome.COMPLETED, null, null);

        Map<String, Object> snapshot = trace.snapshot();
        assertEquals("trace-1", snapshot.get("traceId"));
        assertEquals("turn-1", snapshot.get("turnId"));
        assertEquals("run-1", snapshot.get("runId"));
        assertEquals("completed", snapshot.get("outcome"));
        assertNotNull(snapshot.get("phaseDurations"));
        assertTrue(((Map<?, ?>) snapshot.get("checkpoints"))
                .containsKey("first_event"));
        assertFalse(trace.toJson().isBlank());
    }

    @Test
    void telemetryRecordsATraceExactlyOnce() {
        AiTurnTrace trace = AiTurnTrace.testTrace(
                "trace-1", "puppet", "thread-1",
                System.currentTimeMillis());
        trace.finish(
                AiTurnOutcome.FAILED,
                "tool_calling",
                "tool calls unsupported");
        AiTurnTelemetryRegistry registry = new AiTurnTelemetryRegistry();

        registry.record(trace);
        registry.record(trace);

        Map<String, Object> snapshot = registry.snapshot();
        assertEquals(1L, snapshot.get("completedTurns"));
        assertEquals(1L,
                ((Map<?, ?>) snapshot.get("outcomes")).get("failed"));
        assertEquals(1L,
                ((Map<?, ?>) snapshot.get("errorCategories"))
                        .get("tool_calling"));
    }
}
