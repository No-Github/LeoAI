package org.leo.jmg.mem.packer.jsp;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * JSP 混淆计划的产物格式、用途和允许步骤。
 */
public final class JspObfuscationPlanContext {

    public enum Format {
        JSP,
        JSPX
    }

    public enum Role {
        WEBSHELL,
        PACKER
    }

    private final Format format;
    private final Role role;
    private final Set<String> allowedStepIds;
    private final long seed;

    private JspObfuscationPlanContext(
            Format format, Role role, Set<String> allowedStepIds, long seed) {
        if (format == null || role == null) {
            throw new IllegalArgumentException("混淆产物格式和用途不能为空");
        }
        this.format = format;
        this.role = role;
        this.allowedStepIds = allowedStepIds;
        this.seed = seed;
    }

    public static JspObfuscationPlanContext webShell(Format format) {
        return webShell(format, ThreadLocalRandom.current().nextLong());
    }

    public static JspObfuscationPlanContext webShell(Format format, long seed) {
        return new JspObfuscationPlanContext(format, Role.WEBSHELL, null, seed);
    }

    public static JspObfuscationPlanContext packer(
            Format format, List<String> allowedStepIds) {
        return packer(format, allowedStepIds,
                ThreadLocalRandom.current().nextLong());
    }

    public static JspObfuscationPlanContext packer(
            Format format, List<String> allowedStepIds, long seed) {
        Set<String> allowed = allowedStepIds == null
                ? Collections.<String>emptySet()
                : Collections.unmodifiableSet(
                        new LinkedHashSet<String>(allowedStepIds));
        return new JspObfuscationPlanContext(format, Role.PACKER, allowed, seed);
    }

    Format getFormat() {
        return format;
    }

    Role getRole() {
        return role;
    }

    Set<String> getAllowedStepIds() {
        return allowedStepIds;
    }

    long getSeed() {
        return seed;
    }
}
