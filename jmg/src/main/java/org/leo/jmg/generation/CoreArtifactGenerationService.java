package org.leo.jmg.generation;

import org.leo.jmg.ShellGeneratorConfig;
import org.leo.jmg.generation.pipeline.CoreGenerationPipeline;

/** 只生成 Core，不生成或包装任何外层承载代码。 */
public final class CoreArtifactGenerationService {

    public CoreArtifact generate(CoreArtifactGenerationCommand command) throws Exception {
        if (command == null) {
            throw new IllegalArgumentException("CoreArtifactGenerationCommand 不能为空");
        }
        ShellGeneratorConfig config = command.toConfig();
        GenerationRequest request = GenerationRequest.from(config);
        request.validateCommon();
        byte[] bytecode = new CoreGenerationPipeline(request).generate();
        return new CoreArtifact(
                request.getCoreClassName(),
                bytecode,
                request.getProtocol(),
                request.getTargetJavaVersion(),
                request.getEffectiveServletNamespace(),
                request.getObfuscationSeed());
    }
}
