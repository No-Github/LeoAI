package org.leo.jmg.generation;

import org.leo.core.util.request.ClassNameGenerator;

/**
 * 一次生成执行中的可变工作区。
 *
 * <p>所有中间字节码和最终类名集中在这里，避免把执行状态写入不可变请求。</p>
 */
public final class GenerationWorkspace {

    private String shellClassName;
    private String injectorClassName;
    private byte[] coreClassBytes;
    private byte[] shellClassBytes;
    private byte[] injectorClassBytes;
    private String effectiveUrlPattern;
    private boolean abstractTranslet;
    private final boolean lambdaSuffix;

    private GenerationWorkspace(GenerationRequest request) {
        this.shellClassName = request.getRequestedShellClassName();
        this.injectorClassName = request.getRequestedInjectorClassName();
        this.abstractTranslet = request.isAbstractTransletRequested();
        this.lambdaSuffix = request.isLambdaSuffix();
        this.effectiveUrlPattern = request.getUrlPattern();
    }

    public static GenerationWorkspace create(GenerationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("GenerationRequest 不能为空");
        }
        return new GenerationWorkspace(request);
    }

    public void resolveClassNames() {
        if (isBlank(shellClassName)) {
            shellClassName = ClassNameGenerator.generateServletStyleClassName();
        }
        if (isBlank(injectorClassName)) {
            injectorClassName = ClassNameGenerator.generateServletStyleClassName();
        }
        if (lambdaSuffix) {
            shellClassName = appendLambdaSuffix(shellClassName);
            injectorClassName = appendLambdaSuffix(injectorClassName);
        }
    }

    public String getShellClassName() {
        return shellClassName;
    }

    public String getInjectorClassName() {
        return injectorClassName;
    }

    public String getEffectiveUrlPattern() {
        return effectiveUrlPattern;
    }

    public void setEffectiveUrlPattern(String effectiveUrlPattern) {
        this.effectiveUrlPattern = effectiveUrlPattern;
    }

    public byte[] getCoreClassBytes() {
        return copy(coreClassBytes);
    }

    public void setCoreClassBytes(byte[] coreClassBytes) {
        this.coreClassBytes = copyRequired(coreClassBytes, "coreClassBytes");
    }

    public byte[] getShellClassBytes() {
        return copy(shellClassBytes);
    }

    public void setShellClassBytes(byte[] shellClassBytes) {
        this.shellClassBytes = copyRequired(shellClassBytes, "shellClassBytes");
    }

    public byte[] getInjectorClassBytes() {
        return copy(injectorClassBytes);
    }

    public void setInjectorClassBytes(byte[] injectorClassBytes) {
        this.injectorClassBytes = copyRequired(injectorClassBytes, "injectorClassBytes");
    }

    public boolean isAbstractTranslet() {
        return abstractTranslet;
    }

    public void setAbstractTranslet(boolean abstractTranslet) {
        this.abstractTranslet = abstractTranslet;
    }

    private static byte[] copyRequired(byte[] value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return copy(value);
    }

    private static byte[] copy(byte[] value) {
        return value == null ? null : value.clone();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    static String appendLambdaSuffix(String className) {
        return className.contains("$Lambda$")
                ? className
                : className + "$Proxy0$$Lambda$1";
    }
}
