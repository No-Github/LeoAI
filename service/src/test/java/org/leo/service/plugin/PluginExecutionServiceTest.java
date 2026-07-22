package org.leo.service.plugin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leo.core.entity.Plugin;
import org.leo.core.entity.Puppet;
import org.leo.core.manager.PluginManager;
import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.core.puppet.capability.JavaPluginCapable;
import org.leo.core.puppet.capability.PluginCapable;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PluginExecutionServiceTest {

    private static final String PLUGIN_ID = "architecture-test-plugin";
    private final PluginExecutionService service = new PluginExecutionService();

    @AfterEach
    void removePlugin() {
        PluginManager.getInstance().unload(PLUGIN_ID);
    }

    @Test
    void routesJavaBytecodePluginThroughJavaCapability() throws Exception {
        Plugin plugin = plugin("java", "java", new byte[]{1, 2, 3});
        PluginManager.getInstance().inStallPlugin(plugin);
        JavaNode node = new JavaNode();

        Map<String, Object> result = service.invokePlugin(node, PLUGIN_ID, "{\"key\":\"value\"}");

        assertEquals(200, result.get("code"));
        assertEquals("PluginComponent", node.componentId);
        assertArrayEquals(plugin.getBytecode(), (byte[]) node.params.get("pluginBytecode"));
        assertEquals("value", ((Map<?, ?>) node.params.get("pluginParam")).get("key"));
    }

    @Test
    void routesPhpSourcePluginThroughRuntimeNeutralCapability() throws Exception {
        Plugin plugin = plugin("php", "php", "return 1;".getBytes(StandardCharsets.UTF_8));
        PluginManager.getInstance().inStallPlugin(plugin);
        PhpNode node = new PhpNode();

        Map<String, Object> result = service.invokePlugin(node, PLUGIN_ID, Map.of("key", 1));

        assertEquals(200, result.get("code"));
        assertEquals("return 1;", node.source);
        assertEquals(1, node.params.get("key"));
    }

    private Plugin plugin(String runtime, String type, byte[] payload) {
        Plugin plugin = new Plugin();
        plugin.setPluginId(PLUGIN_ID);
        plugin.setRuntime(runtime);
        plugin.setPluginType(type);
        plugin.setBytecode(payload);
        return plugin;
    }

    private abstract static class TestNode extends AbstractPuppetNode {
        TestNode(String runtime) {
            Puppet puppet = new Puppet();
            puppet.setType(runtime);
            setPuppet(puppet);
        }

        @Override public Set<String> getLoadedComponents() { return Set.of(); }
        @Override public Map<String, Object> testConnection() { return Map.of("code", 200); }
        @Override public void unloadComponent(String componentId) { }
    }

    private static final class JavaNode extends TestNode implements JavaPluginCapable {
        private String componentId;
        private Map<String, Object> params;

        JavaNode() { super("java"); }

        @Override
        public Map<String, Object> invokeComponent(String componentId, Map<String, Object> params) {
            this.componentId = componentId;
            this.params = params;
            return Map.of("code", 200);
        }

        @Override
        public Map<String, Object> execScript(String language, String script) {
            return Map.of("code", 200);
        }
    }

    private static final class PhpNode extends TestNode implements PluginCapable {
        private String source;
        private Map<String, Object> params;

        PhpNode() { super("php"); }

        @Override
        public Map<String, Object> invokeComponent(String componentId, Map<String, Object> params) {
            return Map.of("code", 200);
        }

        @Override
        public Map<String, Object> invokePlugin(String pluginId, String source,
                                                Map<String, Object> params) {
            this.source = source;
            this.params = params;
            return Map.of("code", 200);
        }
    }
}
