package org.leo.jmg.generation.pipeline;

import org.leo.jmg.generation.GenerationPlan;
import org.leo.jmg.generation.GenerationRequest;
import org.leo.jmg.generation.GenerationWorkspace;
import org.leo.jmg.mem.injectortpl.InjectorGenerator;

/**
 * Core → Shell → Injector 字节码生成管线。
 *
 * <p>调用方负责建立 {@code GenerationRandom} 作用域，使后续 Packing 与本管线共享随机序列。</p>
 */
public final class MemoryShellGenerationPipeline {

    private final GenerationRequest request;
    private final GenerationWorkspace workspace;
    private final CoreGenerationPipeline corePipeline;

    public MemoryShellGenerationPipeline(GenerationRequest request,
                                         GenerationWorkspace workspace,
                                         CoreGenerationPipeline corePipeline) {
        if (request == null || workspace == null || corePipeline == null) {
            throw new IllegalArgumentException(
                    "request、workspace 和 corePipeline 不能为空");
        }
        this.request = request;
        this.workspace = workspace;
        this.corePipeline = corePipeline;
    }

    public byte[] generate(GenerationPlan plan) throws Exception {
        if (plan == null
                || plan.getArtifactKind() != GenerationPlan.ArtifactKind.INJECTOR) {
            throw new IllegalArgumentException("MemoryShell 管线需要 INJECTOR 生成计划");
        }
        if (plan.getRequest() != request) {
            throw new IllegalArgumentException("生成计划与 MemoryShell 请求不匹配");
        }

        workspace.resolveClassNames();
        workspace.setAbstractTranslet(plan.isAbstractTranslet());
        workspace.setCoreClassBytes(corePipeline.generate());

        org.leo.jmg.mem.shell.ShellGenerator shellGenerator =
                new org.leo.jmg.mem.shell.ShellGenerator();
        workspace.setShellClassBytes(
                shellGenerator.makeShell(
                        request, workspace, plan.getInjectorDescriptor()));

        InjectorGenerator injectorGenerator = new InjectorGenerator();
        workspace.setInjectorClassBytes(
                injectorGenerator.makeInjector(plan, workspace));
        return workspace.getInjectorClassBytes();
    }
}
