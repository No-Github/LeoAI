package org.leo.ai.agent;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.AiPlan;
import org.leo.core.entity.AiPlanStep;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiAgentFactoryPlanAssociationTest {

    @Test
    void associatesToolWithInProgressStep() {
        AiPlan plan = new AiPlan("test", "goal", List.of(
                step(0), step(1)));
        plan.startStep(1);

        assertEquals(1, AiAgentFactory.findInProgressStepIndex(plan));
    }

    @Test
    void returnsNoAssociationWhenAllStepsArePending() {
        AiPlan plan = new AiPlan("test", "goal", List.of(step(0)));

        assertEquals(-1, AiAgentFactory.findInProgressStepIndex(plan));
    }

    private static AiPlanStep step(int index) {
        return new AiPlanStep(index, "step-" + index,
                null, false, null, 1, List.of());
    }
}
