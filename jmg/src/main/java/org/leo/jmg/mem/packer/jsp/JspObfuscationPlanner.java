package org.leo.jmg.mem.packer.jsp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 校验、排序并编译 JSP 混淆步骤。
 */
public final class JspObfuscationPlanner {

    private JspObfuscationPlanner() {
    }

    public static JspObfuscationPlan compile(
            List<String> stepIds, JspObfuscationPlanContext context) {
        if (context == null) {
            throw new IllegalArgumentException("混淆计划上下文不能为空");
        }
        if (stepIds == null || stepIds.isEmpty()) {
            JspObfuscationPipeline pipeline = JspObfuscationPipeline.builder()
                    .seed(context.getSeed()).build();
            return new JspObfuscationPlan(
                    pipeline,
                    Collections.<String>emptyList(),
                    Collections.<String>emptyList(),
                    Collections.<String>emptyList(),
                    context.getSeed());
        }

        List<String> validIds = new ArrayList<String>();
        List<String> invalidIds = new ArrayList<String>();
        List<String> errors = new ArrayList<String>();
        List<String> warnings = new ArrayList<String>();
        Set<String> seen = new LinkedHashSet<String>();
        for (String id : stepIds) {
            if (id == null || id.trim().isEmpty()) {
                invalidIds.add(String.valueOf(id));
                continue;
            }
            String normalized = id.trim();
            if (!JspObfuscationStepCatalog.contains(normalized)) {
                invalidIds.add(normalized);
                continue;
            }
            if (!seen.add(normalized)) {
                warnings.add("重复步骤已忽略: " + normalized);
                continue;
            }
            validIds.add(normalized);
        }
        if (!invalidIds.isEmpty()) {
            errors.add("未知的 JSP 混淆步骤: " + invalidIds);
        }

        Set<String> selected = new LinkedHashSet<String>(validIds);
        Set<String> reportedConflicts = new HashSet<String>();
        for (String id : validIds) {
            JspObfuscationStepDescriptor descriptor =
                    JspObfuscationStepCatalog.descriptor(id);
            if (context.getFormat() == JspObfuscationPlanContext.Format.JSP
                    && !descriptor.isJspCompatible()) {
                errors.add("步骤 " + id + " 不支持 JSP");
            }
            if (context.getFormat() == JspObfuscationPlanContext.Format.JSPX
                    && !descriptor.isJspxCompatible()) {
                errors.add("步骤 " + id + " 不支持 JSPX");
            }
            if (context.getRole() == JspObfuscationPlanContext.Role.WEBSHELL
                    && !descriptor.isWebshellCompatible()) {
                errors.add("步骤 " + id + " 不适用于 WebShell");
            }
            Set<String> allowed = context.getAllowedStepIds();
            if (allowed != null && !allowed.contains(id)) {
                errors.add("当前 Packer 不支持步骤 " + id);
            }
            for (String incompatible : descriptor.getIncompatibleWith()) {
                if (!selected.contains(incompatible)) {
                    continue;
                }
                String first = id.compareTo(incompatible) <= 0 ? id : incompatible;
                String second = id.compareTo(incompatible) <= 0 ? incompatible : id;
                if (reportedConflicts.add(first + "\u0000" + second)) {
                    errors.add("步骤互斥: " + first + " 与 " + second);
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(joinMessages(errors));
        }

        for (String id : validIds) {
            String warning = JspObfuscationStepCatalog.riskWarning(id);
            if (warning != null) {
                warnings.add(warning);
            }
        }

        List<String> effectiveIds = stableTopologicalSort(validIds);
        if (!effectiveIds.equals(validIds)) {
            warnings.add("步骤顺序已按依赖关系自动调整: " + effectiveIds);
        }

        JspObfuscationPipeline.Builder builder = JspObfuscationPipeline.builder()
                .seed(context.getSeed());
        for (String id : effectiveIds) {
            builder.add(JspObfuscationStepCatalog.step(id));
        }
        return new JspObfuscationPlan(
                builder.build(), validIds, effectiveIds, warnings, context.getSeed());
    }

    private static List<String> stableTopologicalSort(List<String> ids) {
        Map<String, Set<String>> outgoing =
                new LinkedHashMap<String, Set<String>>();
        Map<String, Integer> indegree = new LinkedHashMap<String, Integer>();
        for (String id : ids) {
            outgoing.put(id, new LinkedHashSet<String>());
            indegree.put(id, 0);
        }
        for (String id : ids) {
            for (String after
                    : JspObfuscationStepCatalog.descriptor(id).getMustPrecede()) {
                if (indegree.containsKey(after) && outgoing.get(id).add(after)) {
                    indegree.put(after, indegree.get(after) + 1);
                }
            }
        }

        List<String> result = new ArrayList<String>();
        Set<String> emitted = new HashSet<String>();
        while (result.size() < ids.size()) {
            String next = null;
            for (String id : ids) {
                if (!emitted.contains(id) && indegree.get(id) == 0) {
                    next = id;
                    break;
                }
            }
            if (next == null) {
                throw new IllegalArgumentException(
                        "混淆步骤依赖关系存在循环: " + ids);
            }
            emitted.add(next);
            result.add(next);
            for (String target : outgoing.get(next)) {
                indegree.put(target, indegree.get(target) - 1);
            }
        }
        return result;
    }

    private static String joinMessages(List<String> messages) {
        StringBuilder result = new StringBuilder("混淆计划无效: ");
        for (int index = 0; index < messages.size(); index++) {
            if (index > 0) {
                result.append("; ");
            }
            result.append(messages.get(index));
        }
        return result.toString();
    }
}
