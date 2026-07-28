package org.leo.jmg;

import org.leo.core.util.request.GenerationRandom;
import org.leo.jmg.generation.GenerationPlan;
import org.leo.jmg.generation.GenerationRequest;
import org.leo.jmg.generation.GenerationResult;
import org.leo.jmg.generation.GenerationWorkspace;
import org.leo.jmg.generation.pipeline.CoreGenerationPipeline;
import org.leo.jmg.generation.pipeline.MemoryShellGenerationPipeline;
import org.leo.jmg.generation.pipeline.PackingPipeline;
import org.leo.jmg.generation.pipeline.WebShellGenerationPipeline;

/**
 * Shell 生成器门面。
 *
 * <p>具体实现由 WebShell、MemoryShell 和 Packing 管线承担；本类只管理不可变
 * 请求边界和每次执行独立的随机作用域、工作区与结果。</p>
 */
public class ShellGenerator {

    private final GenerationRequest request;

    public ShellGenerator(GenerationRequest request) {
        this.request = requireRequest(request);
    }

    public GenerationResult generateJspShell() throws Exception {
        return createWebShellPipeline().generate(GenerationPlan.ArtifactKind.JSP);
    }

    public GenerationResult generateJspxShell() throws Exception {
        return createWebShellPipeline().generate(GenerationPlan.ArtifactKind.JSPX);
    }

    /**
     * 生成完整 Injector，并使用解析后的 Packer 格式化。
     */
    public GenerationResult generateFormattedInjector() throws Exception {
        GenerationPlan plan = GenerationPlan.forInjector(request);
        GenerationWorkspace workspace = GenerationWorkspace.create(request);
        CoreGenerationPipeline corePipeline = new CoreGenerationPipeline(request);
        MemoryShellGenerationPipeline memoryShellPipeline =
                new MemoryShellGenerationPipeline(request, workspace, corePipeline);
        PackingPipeline packingPipeline = new PackingPipeline(request);
        try (GenerationRandom.Scope ignored =
                     GenerationRandom.withSeed(request.getObfuscationSeed())) {
            memoryShellPipeline.generate(plan);
            String content = packingPipeline.pack(plan, workspace);
            return GenerationResult.forInjector(plan, workspace, content);
        }
    }

    private WebShellGenerationPipeline createWebShellPipeline() {
        CoreGenerationPipeline corePipeline = new CoreGenerationPipeline(request);
        return new WebShellGenerationPipeline(request, corePipeline);
    }

    private static GenerationRequest requireRequest(
            GenerationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("GenerationRequest 不能为空");
        }
        return request;
    }

}
