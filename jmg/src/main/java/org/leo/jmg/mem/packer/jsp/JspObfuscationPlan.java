package org.leo.jmg.mem.packer.jsp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 已校验、排序且可执行的 JSP 混淆计划。
 */
public final class JspObfuscationPlan {

    private final JspObfuscationPipeline pipeline;
    private final List<String> requestedStepIds;
    private final List<String> effectiveStepIds;
    private final List<String> warnings;
    private final long seed;

    JspObfuscationPlan(
            JspObfuscationPipeline pipeline,
            List<String> requestedStepIds,
            List<String> effectiveStepIds,
            List<String> warnings,
            long seed) {
        this.pipeline = pipeline;
        this.requestedStepIds = immutableCopy(requestedStepIds);
        this.effectiveStepIds = immutableCopy(effectiveStepIds);
        this.warnings = immutableCopy(warnings);
        this.seed = seed;
    }

    public JspObfuscationPipeline getPipeline() {
        return pipeline;
    }

    public List<String> getRequestedStepIds() {
        return requestedStepIds;
    }

    public List<String> getEffectiveStepIds() {
        return effectiveStepIds;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public long getSeed() {
        return seed;
    }

    private static List<String> immutableCopy(List<String> values) {
        return Collections.unmodifiableList(new ArrayList<String>(values));
    }
}
