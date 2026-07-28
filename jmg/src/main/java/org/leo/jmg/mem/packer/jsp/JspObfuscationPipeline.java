package org.leo.jmg.mem.packer.jsp;

import org.leo.core.util.request.GenerationRandom;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 仅负责按顺序执行已编译的 JSP/JSPX 混淆步骤。
 *
 * <p>步骤元数据由 {@link JspObfuscationStepCatalog} 管理，配置校验与排序由
 * {@link JspObfuscationPlanner} 完成。</p>
 */
public final class JspObfuscationPipeline {

    private final List<JspObfuscationStep> steps;
    private final long seed;

    private JspObfuscationPipeline(List<JspObfuscationStep> steps, long seed) {
        this.steps = steps;
        this.seed = seed;
    }

    public String apply(String code) {
        try (GenerationRandom.Scope ignored = GenerationRandom.withSeed(seed)) {
            for (JspObfuscationStep step : steps) {
                code = step.apply(code);
            }
        }
        return code;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static JspObfuscationPipeline jspDefault(long seed) {
        return builder().seed(seed)
                .add(step("SPLIT_STRING_LITERALS"))
                .add(step("CHUNK_PAYLOAD"))
                .add(step("INJECT_SCRIPTLET_NOISE"))
                .add(step("INSERT_SCRIPT_NOISE"))
                .build();
    }

    public static JspObfuscationPipeline jspxDefault(long seed) {
        return builder().seed(seed)
                .add(step("SPLIT_STRING_LITERALS"))
                .add(step("CHUNK_PAYLOAD"))
                .add(step("INJECT_SCRIPTLET_NOISE"))
                .build();
    }

    private static JspObfuscationStep step(String id) {
        return JspObfuscationStepCatalog.step(id);
    }

    public static final class Builder {
        private final List<JspObfuscationStep> steps =
                new ArrayList<JspObfuscationStep>();
        private long seed = ThreadLocalRandom.current().nextLong();

        public Builder add(JspObfuscationStep step) {
            steps.add(Objects.requireNonNull(step, "混淆步骤不能为空"));
            return this;
        }

        public Builder seed(long seed) {
            this.seed = seed;
            return this;
        }

        public JspObfuscationPipeline build() {
            return new JspObfuscationPipeline(
                    new ArrayList<JspObfuscationStep>(steps), seed);
        }
    }
}
