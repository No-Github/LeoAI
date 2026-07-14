package org.leo.jmg;

import java.util.Locale;

/**
 * 生成物使用的 Servlet API 命名空间。
 */
public enum ServletNamespace {
    AUTO("auto"),
    JAVAX("javax"),
    JAKARTA("jakarta");

    private final String value;

    ServletNamespace(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    /** AUTO 保持历史行为，当前解析为 javax。 */
    public ServletNamespace resolve() {
        return this == AUTO ? JAVAX : this;
    }

    public static ServletNamespace parse(String value) {
        if (value == null || value.trim().isEmpty()) {
            return AUTO;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (ServletNamespace namespace : values()) {
            if (namespace.value.equals(normalized)) {
                return namespace;
            }
        }
        throw new IllegalArgumentException(
                "servletNamespace 必须是 auto、javax 或 jakarta，当前值: " + value);
    }
}
