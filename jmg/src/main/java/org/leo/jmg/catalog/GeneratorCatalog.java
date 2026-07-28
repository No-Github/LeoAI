package org.leo.jmg.catalog;

import org.leo.jmg.TransportProtocol;
import org.leo.jmg.mem.ServerType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 生成器能力的唯一目录。
 *
 * <p>所有 server/protocol/injector/template 组合都在此注册。配置校验、实际生成和
 * REST/AI 元数据均从该目录查询，避免各层分别维护协议规则。</p>
 */
public final class GeneratorCatalog {

    private static final String SHELL_FILTER = "LeoFilterTpl";
    private static final String SHELL_FILTER_CHUNK = "LeoFilterChunkTpl";
    private static final String SHELL_VALVE = "LeoValveTpl";
    private static final String SHELL_VALVE_CHUNK = "LeoValveChunkTpl";
    private static final String SHELL_LISTENER = "LeoListenerTpl";
    private static final String SHELL_LISTENER_CHUNK = "LeoListenerChunkTpl";
    private static final String SHELL_SERVLET = "LeoServletTpl";
    private static final String SHELL_SERVLET_CHUNK = "LeoServletChunkTpl";
    private static final String SHELL_WEBSOCKET = "LeoWebSocketTpl";
    private static final String SHELL_INTERCEPTOR = "LeoInterceptorTpl";
    private static final String SHELL_STRUCT2 = "LeoStruct2ActionTpl";
    private static final Map<ServerType, List<InjectorDescriptor>> BY_SERVER;
    private static final List<InjectorDescriptor> ALL;

    static {
        LinkedHashMap<ServerType, List<InjectorDescriptor>> catalog =
                new LinkedHashMap<ServerType, List<InjectorDescriptor>>();

        registerTomcat(catalog);
        registerJbossFamily(catalog);
        registerJetty(catalog);
        registerUndertow(catalog);
        registerWebLogic(catalog);
        registerWebSphere(catalog);
        registerResin(catalog);
        registerGlassfishFamily(catalog);
        registerSpringWebMvc(catalog);
        registerApusic(catalog);
        registerBes(catalog);
        registerInforSuite(catalog);
        registerTongWeb(catalog);
        registerStruct2(catalog);

        List<InjectorDescriptor> all = new ArrayList<InjectorDescriptor>();
        Set<String> uniqueKeys = new LinkedHashSet<String>();
        LinkedHashMap<ServerType, List<InjectorDescriptor>> immutable =
                new LinkedHashMap<ServerType, List<InjectorDescriptor>>();
        for (Map.Entry<ServerType, List<InjectorDescriptor>> entry : catalog.entrySet()) {
            List<InjectorDescriptor> descriptors =
                    Collections.unmodifiableList(new ArrayList<InjectorDescriptor>(entry.getValue()));
            for (InjectorDescriptor descriptor : descriptors) {
                String key = descriptor.getServerType().getValue() + "|"
                        + descriptor.getProtocol().getValue() + "|"
                        + descriptor.getInjectorName();
                if (!uniqueKeys.add(key)) {
                    throw new IllegalStateException("生成器目录存在重复项: " + key);
                }
                all.add(descriptor);
            }
            immutable.put(entry.getKey(), descriptors);
        }
        BY_SERVER = Collections.unmodifiableMap(immutable);
        ALL = Collections.unmodifiableList(all);
    }

    private GeneratorCatalog() {
    }

    public static InjectorDescriptor resolve(String serverType,
                                             String injectorName,
                                             String protocol) {
        ServerType server = ServerType.fromString(serverType);
        if (server == null || injectorName == null) {
            return null;
        }
        TransportProtocol transport;
        try {
            transport = TransportProtocol.parse(protocol);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        String publicName = injectorName.trim();
        List<InjectorDescriptor> descriptors = BY_SERVER.get(server);
        if (descriptors == null) {
            return null;
        }
        for (InjectorDescriptor descriptor : descriptors) {
            if (descriptor.getProtocol() == transport
                    && descriptor.getInjectorName().equals(publicName)) {
                return descriptor;
            }
        }
        return null;
    }

    public static boolean supports(String serverType, String injectorName, String protocol) {
        return resolve(serverType, injectorName, protocol) != null;
    }

    public static List<InjectorDescriptor> getAllDescriptors() {
        return ALL;
    }

    public static Map<String, List<String>> getServerInjectorMap() {
        Map<String, List<String>> result = new LinkedHashMap<String, List<String>>();
        for (Map.Entry<ServerType, List<InjectorDescriptor>> entry : BY_SERVER.entrySet()) {
            Set<String> names = new LinkedHashSet<String>();
            for (InjectorDescriptor descriptor : entry.getValue()) {
                names.add(descriptor.getInjectorName());
            }
            result.put(entry.getKey().getValue(),
                    Collections.unmodifiableList(new ArrayList<String>(names)));
        }
        return Collections.unmodifiableMap(result);
    }

    public static Map<String, Map<String, List<String>>> getProtocolInjectorMap() {
        Map<String, Map<String, List<String>>> result =
                new LinkedHashMap<String, Map<String, List<String>>>();
        for (TransportProtocol protocol : TransportProtocol.values()) {
            Map<String, List<String>> byServer = new LinkedHashMap<String, List<String>>();
            for (Map.Entry<ServerType, List<InjectorDescriptor>> entry : BY_SERVER.entrySet()) {
                List<String> names = new ArrayList<String>();
                for (InjectorDescriptor descriptor : entry.getValue()) {
                    if (descriptor.getProtocol() == protocol) {
                        names.add(descriptor.getInjectorName());
                    }
                }
                if (!names.isEmpty()) {
                    byServer.put(entry.getKey().getValue(),
                            Collections.unmodifiableList(names));
                }
            }
            result.put(protocol.getValue(), Collections.unmodifiableMap(byServer));
        }
        return Collections.unmodifiableMap(result);
    }

    private static void registerTomcat(Map<ServerType, List<InjectorDescriptor>> catalog) {
        List<Entry> entries = entries(
                httpAndChunk("FilterInjector", SHELL_FILTER, SHELL_FILTER_CHUNK,
                        "org.leo.jmg.mem.injectortpl.tomcat.TomcatFilterInjector"),
                httpAndChunk("ValveInjector", SHELL_VALVE, SHELL_VALVE_CHUNK,
                        "org.leo.jmg.mem.injectortpl.tomcat.TomcatValveInjector"),
                websocket("WebSocketInjector",
                        "org.leo.jmg.mem.injectortpl.tomcat.TomcatWebSocketInjector"),
                httpAndChunk("ServletInjector", SHELL_SERVLET, SHELL_SERVLET_CHUNK,
                        "org.leo.jmg.mem.injectortpl.tomcat.TomcatServletInjector"),
                httpAndChunk("ListenerInjector", SHELL_LISTENER, SHELL_LISTENER_CHUNK,
                        "org.leo.jmg.mem.injectortpl.tomcat.TomcatListenerInjector"),
                http("ProxyValveInjector", SHELL_VALVE,
                        "org.leo.jmg.mem.injectortpl.tomcat.TomcatProxyValveInjector"),
                http("UpgradeInjector", SHELL_FILTER,
                        "org.leo.jmg.mem.injectortpl.tomcat.TomcatUpgradeInjector"));
        put(catalog, ServerType.TOMCAT, entries);
    }

    private static void registerJbossFamily(Map<ServerType, List<InjectorDescriptor>> catalog) {
        List<Entry> entries = entries(
                httpAndChunk("FilterInjector", SHELL_FILTER, SHELL_FILTER_CHUNK,
                        "org.leo.jmg.mem.injectortpl.tomcat.TomcatFilterInjector"),
                httpAndChunk("ListenerInjector", SHELL_LISTENER, SHELL_LISTENER_CHUNK,
                        "org.leo.jmg.mem.injectortpl.tomcat.TomcatListenerInjector"),
                http("ValveInjector", SHELL_VALVE,
                        "org.leo.jmg.mem.injectortpl.glassfish.GlassFishValveInjector"),
                http("ProxyValveInjector", SHELL_VALVE,
                        "org.leo.jmg.mem.injectortpl.tomcat.TomcatProxyValveInjector"),
                websocket("WebSocketInjector",
                        "org.leo.jmg.mem.injectortpl.tomcat.TomcatWebSocketInjector"));
        put(catalog, ServerType.JBOSS, entries);
        put(catalog, ServerType.JBOSS_AS, entries);
        put(catalog, ServerType.JBOSS_EAP6, entries);
        put(catalog, ServerType.JBOSS_EAP7, entries);
        put(catalog, ServerType.WILDFLY, entries);
    }

    private static void registerJetty(Map<ServerType, List<InjectorDescriptor>> catalog) {
        put(catalog, ServerType.JETTY, entries(
                httpAndChunk("FilterInjector", SHELL_FILTER, SHELL_FILTER_CHUNK,
                        "org.leo.jmg.mem.injectortpl.jetty.JettyFilterInjector"),
                httpAndChunk("ListenerInjector", SHELL_LISTENER, SHELL_LISTENER_CHUNK,
                        "org.leo.jmg.mem.injectortpl.jetty.JettyListenerInjector"),
                httpAndChunk("ServletInjector", SHELL_SERVLET, SHELL_SERVLET_CHUNK,
                        "org.leo.jmg.mem.injectortpl.jetty.JettyServletInjector"),
                http("CustomizerInjector", SHELL_FILTER,
                        "org.leo.jmg.mem.injectortpl.jetty.JettyCustomizerInjector"),
                websocket("WebSocketInjector",
                        "org.leo.jmg.mem.injectortpl.jetty.JettyWebSocketInjector")));
    }

    private static void registerUndertow(Map<ServerType, List<InjectorDescriptor>> catalog) {
        put(catalog, ServerType.UNDERTOW, entries(
                httpAndChunk("FilterInjector", SHELL_FILTER, SHELL_FILTER_CHUNK,
                        "org.leo.jmg.mem.injectortpl.undertow.UndertowFilterInjector"),
                httpAndChunk("ListenerInjector", SHELL_LISTENER, SHELL_LISTENER_CHUNK,
                        "org.leo.jmg.mem.injectortpl.undertow.UndertowListenerInjector"),
                httpAndChunk("ServletInjector", SHELL_SERVLET, SHELL_SERVLET_CHUNK,
                        "org.leo.jmg.mem.injectortpl.undertow.UndertowServletInjector"),
                websocket("WebSocketInjector",
                        "org.leo.jmg.mem.injectortpl.undertow.UndertowWebSocketInjector")));
    }

    private static void registerWebLogic(Map<ServerType, List<InjectorDescriptor>> catalog) {
        put(catalog, ServerType.WEBLOGIC, entries(
                httpAndChunk("FilterInjector", SHELL_FILTER, SHELL_FILTER_CHUNK,
                        "org.leo.jmg.mem.injectortpl.weblogic.WebLogicFilterInjector"),
                httpAndChunk("ListenerInjector", SHELL_LISTENER, SHELL_LISTENER_CHUNK,
                        "org.leo.jmg.mem.injectortpl.weblogic.WebLogicListenerInjector"),
                httpAndChunk("ServletInjector", SHELL_SERVLET, SHELL_SERVLET_CHUNK,
                        "org.leo.jmg.mem.injectortpl.weblogic.WebLogicServletInjector"),
                websocket("WebSocketInjector",
                        "org.leo.jmg.mem.injectortpl.weblogic.WebLogicWebSocketInjector")));
    }

    private static void registerWebSphere(Map<ServerType, List<InjectorDescriptor>> catalog) {
        put(catalog, ServerType.WEBSPHERE, entries(
                httpAndChunk("FilterInjector", SHELL_FILTER, SHELL_FILTER_CHUNK,
                        "org.leo.jmg.mem.injectortpl.websphere.WebSphereFilterInjector"),
                httpAndChunk("ListenerInjector", SHELL_LISTENER, SHELL_LISTENER_CHUNK,
                        "org.leo.jmg.mem.injectortpl.websphere.WebSphereListenerInjector"),
                httpAndChunk("ServletInjector", SHELL_SERVLET, SHELL_SERVLET_CHUNK,
                        "org.leo.jmg.mem.injectortpl.websphere.WebSphereServletInjector")));
    }

    private static void registerResin(Map<ServerType, List<InjectorDescriptor>> catalog) {
        put(catalog, ServerType.RESIN, entries(
                httpAndChunk("FilterInjector", SHELL_FILTER, SHELL_FILTER_CHUNK,
                        "org.leo.jmg.mem.injectortpl.resin.ResinFilterInjector"),
                httpAndChunk("ListenerInjector", SHELL_LISTENER, SHELL_LISTENER_CHUNK,
                        "org.leo.jmg.mem.injectortpl.resin.ResinListenerInjector"),
                httpAndChunk("ServletInjector", SHELL_SERVLET, SHELL_SERVLET_CHUNK,
                        "org.leo.jmg.mem.injectortpl.resin.ResinServletInjector")));
    }

    private static void registerGlassfishFamily(Map<ServerType, List<InjectorDescriptor>> catalog) {
        List<Entry> entries = entries(
                httpAndChunk("FilterInjector", SHELL_FILTER, SHELL_FILTER_CHUNK,
                        "org.leo.jmg.mem.injectortpl.glassfish.GlassFishFilterInjector"),
                httpAndChunk("ValveInjector", SHELL_VALVE, SHELL_VALVE_CHUNK,
                        "org.leo.jmg.mem.injectortpl.glassfish.GlassFishValveInjector"),
                websocket("WebSocketInjector",
                        "org.leo.jmg.mem.injectortpl.glassfish.GlassFishWebSocketInjector"));
        put(catalog, ServerType.GLASSFISH, entries);
        put(catalog, ServerType.PAYARA, entries);
    }

    private static void registerSpringWebMvc(Map<ServerType, List<InjectorDescriptor>> catalog) {
        put(catalog, ServerType.SPRING_WEBMVC, entries(
                http("InterceptorInjector", SHELL_INTERCEPTOR,
                        "org.leo.jmg.mem.injectortpl.springwebmvc.SpringWebMvcInterceptorInjector"),
                http("MVCInterceptor", SHELL_INTERCEPTOR,
                        "org.leo.jmg.mem.injectortpl.springwebmvc.SpringWebMvcInterceptorInjector"),
                http("ControllerHandlerInjector", SHELL_INTERCEPTOR,
                        "org.leo.jmg.mem.injectortpl.springwebmvc.SpringWebMvcControllerHandlerInjector")));
    }

    private static void registerApusic(Map<ServerType, List<InjectorDescriptor>> catalog) {
        put(catalog, ServerType.APUSIC, entries(
                http("FilterInjector_V9", SHELL_FILTER,
                        "org.leo.jmg.mem.injectortpl.apusic.ApusicFilterInjector"),
                http("FilterInjector_V10", SHELL_FILTER,
                        "org.leo.jmg.mem.injectortpl.apusic.ApusicFilterInjector"),
                httpAndChunk("FilterInjector", SHELL_FILTER, SHELL_FILTER_CHUNK,
                        "org.leo.jmg.mem.injectortpl.apusic.ApusicFilterInjector"),
                httpAndChunk("ListenerInjector", SHELL_LISTENER, SHELL_LISTENER_CHUNK,
                        "org.leo.jmg.mem.injectortpl.apusic.ApusicListenerInjector"),
                httpAndChunk("ServletInjector", SHELL_SERVLET, SHELL_SERVLET_CHUNK,
                        "org.leo.jmg.mem.injectortpl.apusic.ApusicServletInjector")));
    }

    private static void registerBes(Map<ServerType, List<InjectorDescriptor>> catalog) {
        put(catalog, ServerType.BES, entries(
                httpAndChunk("FilterInjector", SHELL_FILTER, SHELL_FILTER_CHUNK,
                        "org.leo.jmg.mem.injectortpl.bes.BesFilterInjector"),
                httpAndChunk("ListenerInjector", SHELL_LISTENER, SHELL_LISTENER_CHUNK,
                        "org.leo.jmg.mem.injectortpl.bes.BesListenerInjector"),
                httpAndChunk("ValveInjector", SHELL_VALVE, SHELL_VALVE_CHUNK,
                        "org.leo.jmg.mem.injectortpl.bes.BesValveInjector")));
    }

    private static void registerInforSuite(Map<ServerType, List<InjectorDescriptor>> catalog) {
        put(catalog, ServerType.INFORSUITE, entries(
                httpAndChunk("FilterInjector", SHELL_FILTER, SHELL_FILTER_CHUNK,
                        "org.leo.jmg.mem.injectortpl.inforsuite.InforSuiteFilterInjector")));
    }

    private static void registerTongWeb(Map<ServerType, List<InjectorDescriptor>> catalog) {
        put(catalog, ServerType.TONGWEB, entries(
                httpAndChunk("FilterInjector", SHELL_FILTER, SHELL_FILTER_CHUNK,
                        "org.leo.jmg.mem.injectortpl.tongweb.TongWebFilterInjector"),
                httpAndChunk("ListenerInjector", SHELL_LISTENER, SHELL_LISTENER_CHUNK,
                        "org.leo.jmg.mem.injectortpl.tongweb.TongWebListenerInjector"),
                httpAndChunk("ValveInjector", SHELL_VALVE, SHELL_VALVE_CHUNK,
                        "org.leo.jmg.mem.injectortpl.tongweb.TongWebValveInjector")));
    }

    private static void registerStruct2(Map<ServerType, List<InjectorDescriptor>> catalog) {
        put(catalog, ServerType.STRUCT2, entries(
                http("ActionInjector", SHELL_STRUCT2,
                        "org.leo.jmg.mem.injectortpl.struct2.Struct2ActionInjectorTpl")));
    }

    private static Entry[] http(String injectorName,
                                String shellTemplate,
                                String injectorTemplate) {
        return new Entry[]{
                new Entry(TransportProtocol.HTTP, injectorName, shellTemplate, injectorTemplate)
        };
    }

    private static Entry[] websocket(String injectorName, String injectorTemplate) {
        return new Entry[]{
                new Entry(TransportProtocol.WEBSOCKET, injectorName,
                        SHELL_WEBSOCKET, injectorTemplate)
        };
    }

    private static Entry[] httpAndChunk(String injectorName,
                                        String httpShellTemplate,
                                        String chunkShellTemplate,
                                        String injectorTemplate) {
        return new Entry[]{
                new Entry(TransportProtocol.HTTP, injectorName,
                        httpShellTemplate, injectorTemplate),
                new Entry(TransportProtocol.HTTP_CHUNK, injectorName,
                        chunkShellTemplate, injectorTemplate)
        };
    }

    private static List<Entry> entries(Entry[]... groups) {
        List<Entry> result = new ArrayList<Entry>();
        for (Entry[] group : groups) {
            Collections.addAll(result, group);
        }
        return result;
    }

    private static void put(Map<ServerType, List<InjectorDescriptor>> catalog,
                            ServerType serverType,
                            List<Entry> entries) {
        List<InjectorDescriptor> descriptors = new ArrayList<InjectorDescriptor>();
        for (Entry entry : entries) {
            descriptors.add(new InjectorDescriptor(serverType, entry.protocol,
                    entry.injectorName, entry.shellTemplate, entry.injectorTemplate));
        }
        catalog.put(serverType, descriptors);
    }

    private static final class Entry {
        private final TransportProtocol protocol;
        private final String injectorName;
        private final String shellTemplate;
        private final String injectorTemplate;

        private Entry(TransportProtocol protocol,
                      String injectorName,
                      String shellTemplate,
                      String injectorTemplate) {
            this.protocol = protocol;
            this.injectorName = injectorName;
            this.shellTemplate = shellTemplate;
            this.injectorTemplate = injectorTemplate;
        }
    }
}
