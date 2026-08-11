package org.leo.jmg.generation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 面向应用入口的生成结果，包含底层生成物和统一摘要。
 */
public final class ShellGenerationOutcome {

    private final GenerationResult generationResult;
    private final Map<String, Object> metadata;
    private final List<GeneratedClassArtifact> classArtifacts;

    public ShellGenerationOutcome(GenerationResult generationResult,
                                  Map<String, Object> metadata) {
        if (generationResult == null) {
            throw new IllegalArgumentException("GenerationResult 不能为空");
        }
        this.generationResult = generationResult;
        this.metadata = Collections.unmodifiableMap(
                new LinkedHashMap<String, Object>(metadata));
        this.classArtifacts = createClassArtifacts(generationResult);
    }

    public GenerationResult getGenerationResult() {
        return generationResult;
    }

    public String getContent() {
        return generationResult.getContent();
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * 返回按生成阶段排序的 Class 产物：Core、Shell、Injector。
     * WebShell 仅包含 Core；内存构建包含全部三个阶段。
     */
    public List<GeneratedClassArtifact> getClassArtifacts() {
        return classArtifacts;
    }

    private static List<GeneratedClassArtifact> createClassArtifacts(
            GenerationResult result) {
        List<GeneratedClassArtifact> artifacts =
                new ArrayList<GeneratedClassArtifact>(3);
        addIfPresent(artifacts, "core", result.getCoreClassName(),
                result.getCoreClassBytes());
        addIfPresent(artifacts, "shell", result.getShellClassName(),
                result.getShellClassBytes());
        addIfPresent(artifacts, "injector", result.getInjectorClassName(),
                result.getInjectorClassBytes());
        return Collections.unmodifiableList(artifacts);
    }

    private static void addIfPresent(List<GeneratedClassArtifact> artifacts,
                                     String role,
                                     String className,
                                     byte[] bytes) {
        if (className != null && bytes != null && bytes.length > 0) {
            artifacts.add(GeneratedClassArtifact.of(role, className, bytes));
        }
    }
}
