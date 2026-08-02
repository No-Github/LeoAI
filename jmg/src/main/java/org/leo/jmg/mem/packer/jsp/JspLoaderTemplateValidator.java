package org.leo.jmg.mem.packer.jsp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** AI 生成的内存马 JSP Loader 模板校验器。 */
public final class JspLoaderTemplateValidator {

    private static final int MAX_TEMPLATE_LENGTH = 64 * 1024;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([^{}\\r\\n]+)\\}\\}");
    private static final Pattern ALLOWED_PLACEHOLDER = Pattern.compile(
            "base64Str|className|VAR:[A-Za-z_][A-Za-z0-9_]*|CLS:[A-Za-z_][A-Za-z0-9_]*");

    private JspLoaderTemplateValidator() {
    }

    public static void validate(String template) {
        if (template == null || template.trim().isEmpty()) {
            throw new IllegalArgumentException("JSP Loader 模板不能为空");
        }
        if (template.length() > MAX_TEMPLATE_LENGTH) {
            throw new IllegalArgumentException("JSP Loader 模板不能超过 " + MAX_TEMPLATE_LENGTH + " 字符");
        }
        if (!template.contains("<%")) {
            throw new IllegalArgumentException("JSP Loader 模板必须包含 JSP scriptlet 或声明块");
        }
        if (count(template, "{{base64Str}}") != 1) {
            throw new IllegalArgumentException("{{base64Str}} 必须且只能出现一次");
        }
        if (!template.contains("defineClass")) {
            throw new IllegalArgumentException("JSP Loader 模板缺少 defineClass 加载步骤");
        }
        if (!template.contains("newInstance")) {
            throw new IllegalArgumentException("JSP Loader 模板缺少 Injector 实例化步骤");
        }

        List<String> unknown = new ArrayList<String>();
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            if (!ALLOWED_PLACEHOLDER.matcher(matcher.group(1)).matches()) {
                unknown.add("{{" + matcher.group(1) + "}}");
            }
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("JSP Loader 模板包含未知占位符: " + unknown);
        }

        String[] forbidden = {
                "runtime.getruntime", "processbuilder", "java.io.file",
                "java.nio.file", "java.net.", "socket(", "system.exit", ".exec("
        };
        String normalized = template.toLowerCase(Locale.ROOT);
        for (String token : forbidden) {
            if (normalized.contains(token)) {
                throw new IllegalArgumentException("JSP Loader 模板包含禁止操作: " + token);
            }
        }
    }

    private static int count(String source, String value) {
        int result = 0;
        int offset = 0;
        while ((offset = source.indexOf(value, offset)) >= 0) {
            result++;
            offset += value.length();
        }
        return result;
    }
}
