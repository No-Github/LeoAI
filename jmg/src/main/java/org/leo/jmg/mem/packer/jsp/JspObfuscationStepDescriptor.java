package org.leo.jmg.mem.packer.jsp;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 单个 JSP 混淆步骤的展示信息和编译约束。
 */
public final class JspObfuscationStepDescriptor {

    private final String id;
    private final String nameZh;
    private final String description;
    private final boolean jspCompatible;
    private final boolean jspxCompatible;
    private final boolean webshellCompatible;
    private final Set<String> incompatibleWith;
    private final Set<String> mustPrecede;

    public JspObfuscationStepDescriptor(
            String id, String nameZh, String description,
            boolean jspCompatible, boolean jspxCompatible,
            boolean webshellCompatible,
            String[] incompatibleWith, String[] mustPrecede) {
        this.id = id;
        this.nameZh = nameZh;
        this.description = description;
        this.jspCompatible = jspCompatible;
        this.jspxCompatible = jspxCompatible;
        this.webshellCompatible = webshellCompatible;
        this.incompatibleWith = immutableSet(incompatibleWith);
        this.mustPrecede = immutableSet(mustPrecede);
    }

    public String getId() {
        return id;
    }

    public String getNameZh() {
        return nameZh;
    }

    public String getDescription() {
        return description;
    }

    public boolean isJspCompatible() {
        return jspCompatible;
    }

    public boolean isJspxCompatible() {
        return jspxCompatible;
    }

    public boolean isWebshellCompatible() {
        return webshellCompatible;
    }

    public Set<String> getIncompatibleWith() {
        return incompatibleWith;
    }

    public Set<String> getMustPrecede() {
        return mustPrecede;
    }

    private static Set<String> immutableSet(String[] values) {
        if (values.length == 0) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(
                new HashSet<String>(Arrays.asList(values)));
    }
}
