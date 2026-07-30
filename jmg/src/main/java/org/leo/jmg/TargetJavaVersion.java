package org.leo.jmg;

import java.util.Locale;

/**
 * 生成物的目标 Java 运行时版本。
 *
 * <p>AUTO 由生成器选择目标；显式版本用于生成前兼容性校验。
 * JDK_9_PLUS 表示模块化但尚未进入 JDK 17 强封装阶段的运行时。
 */
public enum TargetJavaVersion {
    AUTO(0, "auto"),
    JDK_6(6, "6"),
    JDK_7(7, "7"),
    JDK_8(8, "8"),
    JDK_9_PLUS(9, "9+"),
    JDK_17_PLUS(17, "17+");

    private final int major;
    private final String value;

    TargetJavaVersion(int major, String value) {
        this.major = major;
        this.value = value;
    }

    public int getMajor() {
        return major;
    }

    public String getValue() {
        return value;
    }

    public boolean isAuto() {
        return this == AUTO;
    }

    public boolean isAtLeast(int requiredMajor) {
        return !isAuto() && major >= requiredMajor;
    }

    public static TargetJavaVersion parse(String value) {
        if (value == null || value.trim().isEmpty()) {
            return AUTO;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT)
                .replace("JAVA", "JDK")
                .replace("1.", "")
                .replace("_", "")
                .replace("-", "");
        if ("AUTO".equals(normalized)) return AUTO;
        if ("6".equals(normalized) || "JDK6".equals(normalized)) return JDK_6;
        if ("7".equals(normalized) || "JDK7".equals(normalized)) return JDK_7;
        if ("8".equals(normalized) || "JDK8".equals(normalized)) return JDK_8;
        if ("9".equals(normalized) || "9+".equals(normalized)
                || "JDK9".equals(normalized) || "JDK9+".equals(normalized)
                || "11".equals(normalized) || "JDK11".equals(normalized)) {
            return JDK_9_PLUS;
        }
        if ("17".equals(normalized) || "17+".equals(normalized)
                || "JDK17".equals(normalized) || "JDK17+".equals(normalized)
                || "21".equals(normalized) || "JDK21".equals(normalized)) {
            return JDK_17_PLUS;
        }
        throw new IllegalArgumentException(
                "targetJavaVersion 必须是 auto、6、7、8、9+ 或 17+，当前值: " + value);
    }
}
