package org.leo.jmg.mem.packer;

import org.leo.jmg.ServletNamespace;
import org.leo.jmg.TargetJavaVersion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public class ClassPackerConfig {
    private String className;
    private byte[] classBytes;
    private String classBytesBase64Str;
    private boolean byPassJavaModule;
    private TargetJavaVersion targetJavaVersion = TargetJavaVersion.AUTO;
    private ServletNamespace servletNamespace = ServletNamespace.JAVAX;
    private String protocol;
    private String serverType;
    private String injectorName;
    private boolean lambdaSuffix;
    private boolean staticInitialize;
    private boolean shrink = true;
    /** Core、Shell、Injector 的完整类条目，供 Jar 等多类 Packer 使用。 */
    private Map<String, byte[]> classEntries = Collections.emptyMap();
    /**
     * 用户自定义 JSP/JSPX 混淆步骤 ID 列表（有序）。
     * 为 null 或空时 JSP Packer 使用默认 preset；非空时按此顺序构建 pipeline。
     */
    private List<String> jspObfuscationSteps;
    private long obfuscationSeed = ThreadLocalRandom.current().nextLong();

    /**
     * AI 生成的自定义 JSP 模板（含 {{VAR:}} / {{CLS:}} / {{base64Str}} 占位符）。
     * 非 null 时 JSP Packer 优先使用此模板，替代内置模板文件；
     * null 表示使用 Packer 的默认模板。
     */
    private String customTemplate;

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public byte[] getClassBytes() {
        return classBytes;
    }

    public void setClassBytes(byte[] classBytes) {
        this.classBytes = classBytes;
    }

    public String getClassBytesBase64Str() {
        return classBytesBase64Str;
    }

    public void setClassBytesBase64Str(String classBytesBase64Str) {
        this.classBytesBase64Str = classBytesBase64Str;
    }

    public boolean isByPassJavaModule() {
        return byPassJavaModule;
    }

    public void setByPassJavaModule(boolean byPassJavaModule) {
        this.byPassJavaModule = byPassJavaModule;
    }

    public TargetJavaVersion getTargetJavaVersion() {
        return targetJavaVersion;
    }

    public void setTargetJavaVersion(TargetJavaVersion targetJavaVersion) {
        this.targetJavaVersion = targetJavaVersion == null
                ? TargetJavaVersion.AUTO
                : targetJavaVersion;
    }

    public ServletNamespace getServletNamespace() {
        return servletNamespace;
    }

    public void setServletNamespace(ServletNamespace servletNamespace) {
        this.servletNamespace = servletNamespace == null
                ? ServletNamespace.JAVAX
                : servletNamespace;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public String getServerType() {
        return serverType;
    }

    public void setServerType(String serverType) {
        this.serverType = serverType;
    }

    public String getInjectorName() {
        return injectorName;
    }

    public void setInjectorName(String injectorName) {
        this.injectorName = injectorName;
    }

    public boolean isLambdaSuffix() {
        return lambdaSuffix;
    }

    public void setLambdaSuffix(boolean lambdaSuffix) {
        this.lambdaSuffix = lambdaSuffix;
    }

    public boolean isStaticInitialize() {
        return staticInitialize;
    }

    public void setStaticInitialize(boolean staticInitialize) {
        this.staticInitialize = staticInitialize;
    }

    public boolean isShrink() {
        return shrink;
    }

    public void setShrink(boolean shrink) {
        this.shrink = shrink;
    }

    public Map<String, byte[]> getClassEntries() {
        return immutableClassEntries(classEntries);
    }

    public void setClassEntries(Map<String, byte[]> classEntries) {
        this.classEntries = immutableClassEntries(classEntries);
    }

    public List<String> getJspObfuscationSteps() {
        return jspObfuscationSteps;
    }

    public void setJspObfuscationSteps(List<String> jspObfuscationSteps) {
        this.jspObfuscationSteps = jspObfuscationSteps == null
                ? null
                : Collections.unmodifiableList(new ArrayList<String>(jspObfuscationSteps));
    }

    public long getObfuscationSeed() {
        return obfuscationSeed;
    }

    public void setObfuscationSeed(long obfuscationSeed) {
        this.obfuscationSeed = obfuscationSeed;
    }

    public String getCustomTemplate() {
        return customTemplate;
    }

    public void setCustomTemplate(String customTemplate) {
        this.customTemplate = customTemplate;
    }

    private static Map<String, byte[]> immutableClassEntries(
            Map<String, byte[]> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, byte[]> copy = new LinkedHashMap<String, byte[]>();
        for (Map.Entry<String, byte[]> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            copy.put(entry.getKey(), entry.getValue().clone());
        }
        return Collections.unmodifiableMap(copy);
    }
}
