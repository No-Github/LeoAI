package org.leo.jmg.catalog;

/** 注入器在宿主请求链中的挂载点分类。 */
public enum MountType {
    AGENT_FILTER_CHAIN("agent-filter-chain", "Agent FilterChain"),
    AGENT_CONTEXT_VALVE("agent-context-valve", "Agent ContextValve"),
    AGENT_HANDLER("agent-handler", "Agent Handler"),
    AGENT_SERVLET_HANDLER("agent-servlet-handler", "Agent ServletHandler"),
    AGENT_FILTER_MANAGER("agent-filter-manager", "Agent FilterManager"),
    AGENT_SERVLET_CONTEXT("agent-servlet-context", "Agent ServletContext"),
    AGENT_FRAMEWORK_SERVLET("agent-framework-servlet", "Agent FrameworkServlet"),
    FILTER("filter", "Filter 过滤链"),
    LISTENER("listener", "Listener 事件链"),
    SERVLET("servlet", "Servlet 映射"),
    VALVE("valve", "容器 Valve/Pipeline"),
    WEBSOCKET("websocket", "WebSocket Endpoint"),
    INTERCEPTOR("interceptor", "MVC Interceptor"),
    CONTROLLER("controller", "MVC Controller Handler"),
    WEB_FILTER("web-filter", "WebFlux WebFilter"),
    HANDLER_METHOD("handler-method", "WebFlux HandlerMethod"),
    HANDLER_FUNCTION("handler-function", "WebFlux HandlerFunction"),
    NETTY_HANDLER("netty-handler", "Netty ChannelHandler"),
    DUBBO_SERVICE("dubbo-service", "Dubbo Service"),
    CUSTOMIZER("customizer", "容器 Customizer"),
    ACTION("action", "Action 映射"),
    HANDLER("handler", "容器 Handler"),
    UPGRADE("upgrade", "HTTP UpgradeProtocol");

    private final String value;
    private final String label;

    MountType(String value, String label) {
        this.value = value;
        this.label = label;
    }

    public String getValue() {
        return value;
    }

    public String getLabel() {
        return label;
    }

    /** 仅路径映射型挂载点消费 urlPattern；全局请求链挂载点不展示该配置。 */
    public boolean supportsUrlPattern() {
        return this == FILTER
                || this == SERVLET
                || this == WEBSOCKET
                || this == CONTROLLER
                || this == HANDLER_METHOD
                || this == HANDLER_FUNCTION
                || this == DUBBO_SERVICE
                || this == ACTION;
    }

    static MountType fromInjectorName(String injectorName) {
        String name = injectorName == null ? "" : injectorName.toLowerCase();
        if (name.contains("agentfilterchain")) return AGENT_FILTER_CHAIN;
        if (name.contains("agentcontextvalve")) return AGENT_CONTEXT_VALVE;
        if (name.contains("agentservlethandler")) return AGENT_SERVLET_HANDLER;
        if (name.contains("agentfiltermanager")) return AGENT_FILTER_MANAGER;
        if (name.contains("agentservletcontext")) return AGENT_SERVLET_CONTEXT;
        if (name.contains("agentframeworkservlet")) return AGENT_FRAMEWORK_SERVLET;
        if (name.contains("agenthandler")) return AGENT_HANDLER;
        if (name.contains("dubboservice")) return DUBBO_SERVICE;
        if (name.contains("handlerfunction")) return HANDLER_FUNCTION;
        if (name.contains("handlermethod")) return HANDLER_METHOD;
        if (name.contains("nettyhandler")) return NETTY_HANDLER;
        if (name.contains("webfilter")) return WEB_FILTER;
        if (name.contains("filter")) return FILTER;
        if (name.contains("listener")) return LISTENER;
        if (name.contains("servlet")) return SERVLET;
        if (name.contains("websocket")) return WEBSOCKET;
        if (name.contains("proxyvalve") || name.contains("valve")) return VALVE;
        if (name.contains("controller")) return CONTROLLER;
        if (name.contains("interceptor")) return INTERCEPTOR;
        if (name.contains("customizer")) return CUSTOMIZER;
        if (name.contains("action")) return ACTION;
        if (name.contains("upgrade")) return UPGRADE;
        if (name.contains("handler")) return HANDLER;
        throw new IllegalArgumentException("未识别的挂载类型: " + injectorName);
    }
}
