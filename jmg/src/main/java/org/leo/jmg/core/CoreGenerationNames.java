package org.leo.jmg.core;

import org.leo.jmg.ShellGeneratorConfig;

/**
 * LeoCore 内部成员名的不可变快照。
 */
public final class CoreGenerationNames {

    private final String methodAction;
    private final String methodTestConn;
    private final String methodRedirect;
    private final String methodLoadComponent;
    private final String methodInvokeComponent;
    private final String fieldParams;
    private final String fieldResults;
    private final String fieldHostId;
    private final String fieldComponents;

    private CoreGenerationNames(ShellGeneratorConfig config) {
        this.methodAction = config.getMethodAction();
        this.methodTestConn = config.getMethodTestConn();
        this.methodRedirect = config.getMethodRedirect();
        this.methodLoadComponent = config.getMethodLoadComponent();
        this.methodInvokeComponent = config.getMethodInvokeComponent();
        this.fieldParams = config.getFieldParams();
        this.fieldResults = config.getFieldResults();
        this.fieldHostId = config.getFieldHostId();
        this.fieldComponents = config.getFieldComponents();
    }

    public static CoreGenerationNames from(ShellGeneratorConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("ShellGeneratorConfig 不能为空");
        }
        return new CoreGenerationNames(config);
    }

    public String getMethodAction() {
        return methodAction;
    }

    public String getMethodTestConn() {
        return methodTestConn;
    }

    public String getMethodRedirect() {
        return methodRedirect;
    }

    public String getMethodLoadComponent() {
        return methodLoadComponent;
    }

    public String getMethodInvokeComponent() {
        return methodInvokeComponent;
    }

    public String getFieldParams() {
        return fieldParams;
    }

    public String getFieldResults() {
        return fieldResults;
    }

    public String getFieldHostId() {
        return fieldHostId;
    }

    public String getFieldComponents() {
        return fieldComponents;
    }
}
