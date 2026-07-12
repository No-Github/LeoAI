package org.leo.jmg;

import org.junit.jupiter.api.Test;
import org.leo.jmg.mem.packer.jsp.JspObfuscationPipeline;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JspObfuscationPipelineTest {

    @Test
    void rejectsUnknownNullAndBlankStepIds() {
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> JspObfuscationPipeline.fromStepIds(Arrays.asList("NOT_A_STEP")));
        assertTrue(unknown.getMessage().contains("NOT_A_STEP"));

        assertThrows(IllegalArgumentException.class,
                () -> JspObfuscationPipeline.fromStepIds(Arrays.asList("CHUNK_PAYLOAD", null)));
        assertThrows(IllegalArgumentException.class,
                () -> JspObfuscationPipeline.fromStepIds(Arrays.asList(" ")));
    }

    @Test
    void trimsKnownStepIdsBeforeBuildingPipeline() {
        JspObfuscationPipeline pipeline = JspObfuscationPipeline.fromStepIds(
                Arrays.asList("  NORMALIZE_WHITESPACE  "));

        assertEquals("plain text", pipeline.apply("plain text"));
    }

    @Test
    void descriptorConstraintSetsAreImmutable() {
        JspObfuscationPipeline.StepDescriptor descriptor = null;
        for (JspObfuscationPipeline.StepDescriptor candidate
                : JspObfuscationPipeline.getStepDescriptors()) {
            if (!candidate.getIncompatibleWith().isEmpty()) {
                descriptor = candidate;
                break;
            }
        }

        assertTrue(descriptor != null, "缺少带互斥约束的步骤描述");
        JspObfuscationPipeline.StepDescriptor selected = descriptor;
        assertThrows(UnsupportedOperationException.class,
                () -> selected.getIncompatibleWith().clear());
    }
}
