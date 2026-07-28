package org.leo.jmg.generation;

import org.leo.core.entity.Disguise;
import org.leo.jmg.ServletNamespace;
import org.leo.jmg.ShellGeneratorConfig;
import org.leo.jmg.TargetJavaVersion;
import org.leo.jmg.TransportProtocol;
import org.leo.jmg.core.CoreGenerationNames;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次生成请求的不可变快照，只保存调用方输入，不承载生成过程中的字节码和派生状态。
 */
public final class GenerationRequest {

    private final boolean requestDisguisePresent;
    private final boolean responseDisguisePresent;
    private final String requestDecodeBody;
    private final String responseEncodeBody;
    private final String coreClassName;
    private final int responseCode;
    private final TransportProtocol protocol;
    private final String serverType;
    private final String injectorName;
    private final String packerType;
    private final TargetJavaVersion targetJavaVersion;
    private final ServletNamespace servletNamespace;
    private final String headerName;
    private final String headerValue;
    private final String requestedShellClassName;
    private final String requestedInjectorClassName;
    private final String urlPattern;
    private final boolean abstractTransletRequested;
    private final boolean bypassJavaModule;
    private final List<String> jspObfuscationSteps;
    private final long obfuscationSeed;
    private final String customJspTemplate;
    private final CoreGenerationNames coreGenerationNames;

    private GenerationRequest(ShellGeneratorConfig config) {
        Disguise requestDisguise = config.getReqDisguise();
        Disguise responseDisguise = config.getRespDisguise();
        this.requestDisguisePresent = requestDisguise != null;
        this.responseDisguisePresent = responseDisguise != null;
        this.requestDecodeBody = requestDisguise == null
                ? null
                : requestDisguise.getDecodeBody();
        this.responseEncodeBody = responseDisguise == null
                ? null
                : responseDisguise.getEncodeBody();
        this.coreClassName = config.getCoreClassName();
        this.responseCode = config.getRespCode();
        this.protocol = TransportProtocol.parse(config.getProtocol());
        this.serverType = config.getServerType();
        this.injectorName = config.getShellType();
        this.packerType = config.getPackerType();
        this.targetJavaVersion = config.getTargetJavaVersion();
        this.servletNamespace = config.getServletNamespace();
        this.headerName = config.getHeaderName();
        this.headerValue = config.getHeaderValue();
        this.requestedShellClassName = config.getRequestedShellClassName();
        this.requestedInjectorClassName = config.getRequestedInjectorClassName();
        this.urlPattern = config.getUrlPattern();
        this.abstractTransletRequested = config.isAbstractTranslet();
        this.bypassJavaModule = config.isByPassJavaModule();
        List<String> steps = config.getJspObfuscationSteps();
        this.jspObfuscationSteps = steps == null
                ? null
                : Collections.unmodifiableList(new ArrayList<String>(steps));
        this.obfuscationSeed = config.getObfuscationSeed();
        this.customJspTemplate = config.getCustomJspTemplate();
        this.coreGenerationNames = CoreGenerationNames.from(config);
    }

    public static GenerationRequest from(ShellGeneratorConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("ShellGeneratorConfig 不能为空");
        }
        return new GenerationRequest(config);
    }

    void validateCommon() {
        if (!requestDisguisePresent) {
            throw new IllegalArgumentException("reqDisguise不能为空");
        }
        if (!responseDisguisePresent) {
            throw new IllegalArgumentException("respDisguise不能为空");
        }
        if (servletNamespace.resolve() == ServletNamespace.JAKARTA
                && !targetJavaVersion.isAuto()
                && targetJavaVersion.getMajor() < 8) {
            throw new IllegalArgumentException("jakarta.servlet 最低要求 JDK 8，当前目标为 JDK "
                    + targetJavaVersion.getValue());
        }
    }

    /**
     * 为执行创建编码策略快照，避免旧 Disguise 对象在生成期间被修改。
     */
    public Disguise createRequestDisguiseSnapshot() {
        Disguise snapshot = new Disguise();
        snapshot.setDecodeBody(requestDecodeBody);
        return snapshot;
    }

    public Disguise createResponseDisguiseSnapshot() {
        Disguise snapshot = new Disguise();
        snapshot.setEncodeBody(responseEncodeBody);
        return snapshot;
    }

    public String getCoreClassName() {
        return coreClassName;
    }

    public int getResponseCode() {
        return responseCode;
    }

    public TransportProtocol getProtocol() {
        return protocol;
    }

    public String getServerType() {
        return serverType;
    }

    public String getInjectorName() {
        return injectorName;
    }

    public String getPackerType() {
        return packerType;
    }

    public TargetJavaVersion getTargetJavaVersion() {
        return targetJavaVersion;
    }

    public ServletNamespace getServletNamespace() {
        return servletNamespace;
    }

    public ServletNamespace getEffectiveServletNamespace() {
        return servletNamespace.resolve();
    }

    public String getHeaderName() {
        return headerName;
    }

    public String getHeaderValue() {
        return headerValue;
    }

    public String getRequestedShellClassName() {
        return requestedShellClassName;
    }

    public String getRequestedInjectorClassName() {
        return requestedInjectorClassName;
    }

    public String getUrlPattern() {
        return urlPattern;
    }

    public boolean isAbstractTransletRequested() {
        return abstractTransletRequested;
    }

    public boolean isBypassJavaModule() {
        return bypassJavaModule;
    }

    public List<String> getJspObfuscationSteps() {
        return jspObfuscationSteps;
    }

    public long getObfuscationSeed() {
        return obfuscationSeed;
    }

    public String getCustomJspTemplate() {
        return customJspTemplate;
    }

    public CoreGenerationNames getCoreGenerationNames() {
        return coreGenerationNames;
    }
}
