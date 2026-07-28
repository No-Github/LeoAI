package org.leo.jmg.generation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 面向应用入口的生成结果，包含底层生成物和统一摘要。
 */
public final class ShellGenerationOutcome {

    private final GenerationResult generationResult;
    private final Map<String, Object> metadata;

    public ShellGenerationOutcome(GenerationResult generationResult,
                                  Map<String, Object> metadata) {
        if (generationResult == null) {
            throw new IllegalArgumentException("GenerationResult 不能为空");
        }
        this.generationResult = generationResult;
        this.metadata = Collections.unmodifiableMap(
                new LinkedHashMap<String, Object>(metadata));
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
}
