package org.leo.jmg.generation.pipeline;

import org.leo.jmg.generation.GenerationPlan;
import org.leo.jmg.generation.GenerationRequest;
import org.leo.jmg.generation.GenerationWorkspace;
import org.leo.jmg.mem.packer.ClassPackerConfig;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 将最终 Injector 字节码交给已解析的 Packer。
 */
public final class PackingPipeline {

    private final GenerationRequest request;

    public PackingPipeline(GenerationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("GenerationRequest 不能为空");
        }
        this.request = request;
    }

    public String pack(GenerationPlan plan,
                       GenerationWorkspace workspace) throws Exception {
        if (plan == null || plan.getPacker() == null || workspace == null) {
            throw new IllegalArgumentException(
                    "Packing 管线需要已解析的 Packer 和生成工作区");
        }
        if (plan.getRequest() != request) {
            throw new IllegalArgumentException("生成计划与 Packing 请求不匹配");
        }
        byte[] bytecode = workspace.getInjectorClassBytes();
        if (bytecode == null) {
            throw new IllegalStateException("Injector 字节码尚未生成");
        }

        ClassPackerConfig config = new ClassPackerConfig();
        config.setClassName(workspace.getInjectorClassName());
        config.setClassBytes(bytecode);
        config.setClassBytesBase64Str(
                Base64.getEncoder().encodeToString(bytecode));
        config.setByPassJavaModule(request.isBypassJavaModuleEffective());
        config.setTargetJavaVersion(request.getTargetJavaVersion());
        config.setServletNamespace(request.getEffectiveServletNamespace());
        config.setProtocol(request.getProtocol().getValue());
        config.setServerType(request.getServerType());
        config.setInjectorName(request.getInjectorName());
        config.setLambdaSuffix(request.isLambdaSuffix());
        config.setStaticInitialize(request.isStaticInitialize());
        config.setShrink(request.isShrink());
        Map<String, byte[]> classEntries = new LinkedHashMap<String, byte[]>();
        addClassEntry(classEntries, request.getCoreClassName(),
                workspace.getCoreClassBytes());
        addClassEntry(classEntries, workspace.getShellClassName(),
                workspace.getShellClassBytes());
        addClassEntry(classEntries, workspace.getInjectorClassName(), bytecode);
        config.setClassEntries(classEntries);
        config.setJspObfuscationSteps(request.getJspObfuscationSteps());
        config.setObfuscationSeed(request.getObfuscationSeed());
        config.setCustomTemplate(request.getCustomJspTemplate());
        return plan.getPacker().pack(config);
    }

    private static void addClassEntry(Map<String, byte[]> entries,
                                      String className,
                                      byte[] bytes) {
        if (className != null && bytes != null) {
            entries.put(className.replace('.', '/') + ".class", bytes);
        }
    }
}
