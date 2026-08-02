package org.leo.jmg.generation;

import org.leo.jmg.TransportProtocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 可编辑 WebShell 外层模板的受控契约。
 *
 * <p>AI 只能调整模板结构并排列阶段占位符；承载 Core 的关键代码由平台最终注入。
 */
public final class WebShellWrapperContract {

    public static final String DECLARE_STATE = "{{DECLARE_STATE}}";
    public static final String LOAD_CORE = "{{LOAD_CORE}}";
    public static final String READ_REQUEST = "{{READ_REQUEST}}";
    public static final String INVOKE_CORE = "{{INVOKE_CORE}}";
    public static final String WRITE_RESPONSE = "{{WRITE_RESPONSE}}";

    private static final int MAX_TEMPLATE_LENGTH = 64 * 1024;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([^{}\\r\\n]+)\\}\\}");
    private static final Pattern JSP_IMPORT = Pattern.compile("\\bimport\\s*=", Pattern.CASE_INSENSITIVE);
    private static final List<String> PHASES;
    private static final List<String> FORBIDDEN_TOKENS;

    static {
        List<String> phases = new ArrayList<String>();
        phases.add(DECLARE_STATE);
        phases.add(LOAD_CORE);
        phases.add(READ_REQUEST);
        phases.add(INVOKE_CORE);
        phases.add(WRITE_RESPONSE);
        PHASES = Collections.unmodifiableList(phases);

        List<String> forbidden = new ArrayList<String>();
        forbidden.add("runtime.getruntime");
        forbidden.add("processbuilder");
        forbidden.add("java.io.file");
        forbidden.add("java.nio.file");
        forbidden.add("java.net.");
        forbidden.add("socket(");
        forbidden.add("system.exit");
        forbidden.add(".exec(");
        forbidden.add("setsecuritymanager");
        forbidden.add("java.lang.reflect");
        forbidden.add("class.forname");
        forbidden.add("classloader");
        forbidden.add("defineclass");
        forbidden.add("newinstance");
        forbidden.add("getdeclaredmethod");
        forbidden.add(".invoke(");
        forbidden.add("<%@ include");
        forbidden.add("<%@ taglib");
        forbidden.add("<jsp:directive.include");
        forbidden.add("<jsp:include");
        forbidden.add("<jsp:forward");
        forbidden.add("<jsp:usebean");
        forbidden.add("<jsp:plugin");
        forbidden.add("<!doctype");
        FORBIDDEN_TOKENS = Collections.unmodifiableList(forbidden);
    }

    private final GenerationPlan.ArtifactKind artifactKind;
    private final TransportProtocol protocol;

    private WebShellWrapperContract(GenerationPlan.ArtifactKind artifactKind,
                                    TransportProtocol protocol) {
        this.artifactKind = artifactKind;
        this.protocol = protocol;
    }

    public static WebShellWrapperContract create(String artifactType, String protocol) {
        String normalized = artifactType == null
                ? "" : artifactType.trim().toUpperCase(Locale.ROOT);
        GenerationPlan.ArtifactKind kind;
        if ("JSP".equals(normalized)) kind = GenerationPlan.ArtifactKind.JSP;
        else if ("JSPX".equals(normalized)) kind = GenerationPlan.ArtifactKind.JSPX;
        else throw new IllegalArgumentException("Wrapper 类型必须是 JSP 或 JSPX: " + artifactType);

        TransportProtocol transport = TransportProtocol.parse(protocol);
        if (transport == TransportProtocol.WEBSOCKET) {
            throw new IllegalArgumentException("WebShell Wrapper 暂不支持 websocket");
        }
        return new WebShellWrapperContract(kind, transport);
    }

    public void validate(String template) {
        if (template == null || template.trim().isEmpty()) {
            throw new IllegalArgumentException("Wrapper 模板不能为空");
        }
        if (template.length() > MAX_TEMPLATE_LENGTH) {
            throw new IllegalArgumentException("Wrapper 模板不能超过 " + MAX_TEMPLATE_LENGTH + " 字符");
        }
        if (template.contains("/*") || template.contains("*/") || template.contains("<%--")) {
            throw new IllegalArgumentException("Wrapper 模板不允许块注释，避免阶段占位符被注释");
        }
        if (JSP_IMPORT.matcher(template).find()) {
            throw new IllegalArgumentException("Wrapper 模板不允许 JSP import 属性");
        }
        String normalizedTemplate = template.toLowerCase(Locale.ROOT);
        for (String token : FORBIDDEN_TOKENS) {
            if (normalizedTemplate.contains(token)) {
                throw new IllegalArgumentException("Wrapper 模板包含禁止操作: " + token);
            }
        }

        int previous = -1;
        for (String phase : PHASES) {
            if (count(template, phase) != 1) {
                throw new IllegalArgumentException("阶段占位符必须且只能出现一次: " + phase);
            }
            if (!isStandaloneLine(template, phase)) {
                throw new IllegalArgumentException("阶段占位符必须独占一行: " + phase);
            }
            int current = template.indexOf(phase);
            if (current <= previous) {
                throw new IllegalArgumentException("阶段占位符顺序无效，必须按契约顺序排列");
            }
            previous = current;
        }

        Matcher matcher = PLACEHOLDER.matcher(template);
        Set<String> unresolved = new LinkedHashSet<String>();
        while (matcher.find()) {
            String value = "{{" + matcher.group(1) + "}}";
            if (!PHASES.contains(value)) unresolved.add(value);
        }
        if (!unresolved.isEmpty()) {
            throw new IllegalArgumentException("Wrapper 模板包含未知占位符: " + unresolved);
        }

        if (artifactKind == GenerationPlan.ArtifactKind.JSP) {
            validateJspContainer(template);
        } else {
            validateJspxContainer(template);
        }
    }

    public String getBaselineTemplate() {
        if (artifactKind == GenerationPlan.ArtifactKind.JSPX) {
            return "<jsp:root version=\"2.0\" xmlns:jsp=\"http://java.sun.com/JSP/Page\">\n"
                    + "    <jsp:directive.page contentType=\"application/octet-stream\"/>\n"
                    + "    <jsp:scriptlet><![CDATA[\n"
                    + indentPhases("        ")
                    + "    ]]></jsp:scriptlet>\n"
                    + "</jsp:root>";
        }
        return "<%@ page contentType=\"application/octet-stream\" %>\n"
                + "<%\n" + indentPhases("    ") + "%>";
    }

    public List<String> getRequiredPhases() {
        return PHASES;
    }

    public String getArtifactType() {
        return artifactKind.name();
    }

    public TransportProtocol getProtocol() {
        return protocol;
    }

    public List<String> getRules() {
        List<String> rules = new ArrayList<String>();
        rules.add("五个阶段占位符必须各出现一次、独占一行并保持顺序");
        rules.add("不得修改或展开阶段占位符，真实 Core Payload 由平台最终注入");
        rules.add("可调整 JSP/JSPX 布局、辅助声明、无副作用控制流和业务外观");
        rules.add("不得增加命令执行、文件访问或外部网络访问");
        rules.add("只返回模板源码，不返回 Markdown 代码块或说明文字");
        return Collections.unmodifiableList(rules);
    }

    private void validateJspContainer(String template) {
        if (!template.contains("<%") || !template.contains("%>")) {
            throw new IllegalArgumentException("JSP Wrapper 必须包含 scriptlet");
        }
        if (template.contains("<jsp:root")) {
            throw new IllegalArgumentException("JSP Wrapper 不能使用 JSPX 根元素");
        }
        for (String phase : PHASES) {
            int position = template.indexOf(phase);
            if (template.lastIndexOf("<%", position) <= template.lastIndexOf("%>", position)) {
                throw new IllegalArgumentException("阶段占位符必须位于 JSP scriptlet 内: " + phase);
            }
        }
    }

    private void validateJspxContainer(String template) {
        if (!template.contains("<jsp:root")
                || !template.contains("<jsp:scriptlet")
                || !template.contains("<![CDATA[")
                || !template.contains("]]>")
                || !template.contains("</jsp:root>")) {
            throw new IllegalArgumentException("JSPX Wrapper 必须使用 jsp:root、scriptlet 和 CDATA");
        }
        for (String phase : PHASES) {
            int position = template.indexOf(phase);
            if (template.lastIndexOf("<jsp:scriptlet", position)
                    <= template.lastIndexOf("</jsp:scriptlet>", position)) {
                throw new IllegalArgumentException(
                        "阶段占位符必须位于 JSPX scriptlet 内: " + phase);
            }
            if (template.lastIndexOf("<![CDATA[", position) <= template.lastIndexOf("]]>", position)) {
                throw new IllegalArgumentException("阶段占位符必须位于 JSPX CDATA 内: " + phase);
            }
        }
    }

    private static String indentPhases(String indent) {
        StringBuilder result = new StringBuilder();
        for (String phase : PHASES) result.append(indent).append(phase).append('\n');
        return result.toString();
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

    private static boolean isStandaloneLine(String template, String phase) {
        String[] lines = template.split("\\r?\\n", -1);
        for (String line : lines) {
            if (phase.equals(line.trim())) return true;
        }
        return false;
    }
}
