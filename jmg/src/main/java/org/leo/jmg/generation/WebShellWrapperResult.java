package org.leo.jmg.generation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 已验证并注入 Core 后的 WebShell Wrapper 结果。 */
public final class WebShellWrapperResult {

    private final String content;
    private final Map<String, Object> metadata;

    public WebShellWrapperResult(String content, Map<String, Object> metadata) {
        if (content == null || content.isEmpty()) {
            throw new IllegalArgumentException("Wrapper 结果不能为空");
        }
        this.content = content;
        this.metadata = Collections.unmodifiableMap(
                new LinkedHashMap<String, Object>(metadata));
    }

    public String getContent() {
        return content;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }
}
