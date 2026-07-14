package org.leo.ai.tools.platform;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leo.ai.agent.AiToolContext;
import org.leo.ai.platform.PlatformAiState;
import org.leo.ai.platform.PlatformAiStateStore;
import org.leo.core.entity.AiPlanStatus;
import org.leo.core.entity.AiStepStatus;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PlatformPlanToolsTest {

    private static final String STATE_ID = "platform-plan-tools-test";
    private final PlatformPlanTools tools = new PlatformPlanTools();

    @AfterEach
    void cleanUp() {
        AiToolContext.clear();
        PlatformAiStateStore.remove(STATE_ID);
    }

    @Test
    void createsUpdatesAndCompletesPlanInPlatformState() {
        PlatformAiState state = PlatformAiStateStore.create(STATE_ID);
        AiToolContext.setFromMemoryId(STATE_ID);

        tools.createPlan("批量核查", "核查平台资源", List.of(
                Map.of("description", "查询 Puppet 列表", "toolHint", "getAllPuppet"),
                Map.of("description", "汇总异常节点", "dependsOn", List.of(0))
        ), 0);

        assertNotNull(state.getCurrentPlan());
        assertEquals(2, state.getCurrentPlan().getSteps().size());
        assertEquals("plan", state.getAiSseEventQueue().poll().name());

        tools.updatePlanStep(0, "start", null);
        tools.updatePlanStep(0, "complete", "发现 3 个节点");
        tools.updatePlanStep(1, "start", null);
        assertEquals(AiStepStatus.IN_PROGRESS, state.getCurrentPlan().getSteps().get(1).getStatus());

        tools.updatePlanStep(1, "complete", "发现 1 个异常节点");
        tools.completePlan("平台资源核查完成");
        assertEquals(AiPlanStatus.COMPLETED, state.getCurrentPlan().getStatus());
        assertEquals("平台资源核查完成", state.getCurrentPlan().getFinalSummary());
    }
}
