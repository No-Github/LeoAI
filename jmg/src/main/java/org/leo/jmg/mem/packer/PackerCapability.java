package org.leo.jmg.mem.packer;

/**
 * Packer 生成结果对目标运行环境的能力要求。
 */
public enum PackerCapability {
    JAVASCRIPT_ENGINE("javascript-engine", "JavaScript ScriptEngine");

    private final String value;
    private final String displayName;

    PackerCapability(String value, String displayName) {
        this.value = value;
        this.displayName = displayName;
    }

    public String getValue() {
        return value;
    }

    public String getDisplayName() {
        return displayName;
    }
}
