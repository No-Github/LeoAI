package org.leo.service.puppetnode;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.Puppet;
import org.leo.core.entity.User;
import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.core.runtime.PuppetNodeCreationContext;
import org.leo.core.runtime.PuppetRuntime;
import org.leo.core.runtime.PuppetRuntimeModule;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PuppetNodeFactoryRuntimeModuleTest {

    @Test
    void selectsJavaAndPhpThroughTheSameRuntimeModuleSpi() throws Exception {
        PuppetNodeFactory factory = new PuppetNodeFactory(null, List.of(
                module(PuppetRuntime.JAVA, true),
                module(PuppetRuntime.PHP, true)));

        assertEquals(PuppetRuntime.JAVA, factory.createLiveNode(puppet("java"), null).getRuntime());
        assertEquals(PuppetRuntime.PHP, factory.createLiveNode(puppet("php"), null).getRuntime());
    }

    @Test
    void rejectsDuplicateOrUnavailableRuntimeModules() {
        assertThrows(IllegalStateException.class, () -> new PuppetNodeFactory(null, List.of(
                module(PuppetRuntime.JAVA, true),
                module(PuppetRuntime.JAVA, true))));

        PuppetNodeFactory factory = new PuppetNodeFactory(null, List.of(
                module(PuppetRuntime.JAVA, true),
                module(PuppetRuntime.PHP, false)));
        assertThrows(IllegalArgumentException.class,
                () -> factory.createLiveNode(puppet("php"), null));
    }

    private static Puppet puppet(String type) {
        Puppet puppet = new Puppet();
        puppet.setType(type);
        return puppet;
    }

    private static PuppetRuntimeModule module(PuppetRuntime runtime, boolean ready) {
        return new PuppetRuntimeModule() {
            @Override
            public PuppetRuntime getRuntime() {
                return runtime;
            }

            @Override
            public boolean isReady() {
                return ready;
            }

            @Override
            public AbstractPuppetNode createNode(Puppet puppet,
                                                 User user,
                                                 PuppetNodeCreationContext context) {
                RuntimeNode node = new RuntimeNode();
                node.setPuppet(puppet);
                node.setUser(user);
                return node;
            }
        };
    }

    private static final class RuntimeNode extends AbstractPuppetNode {
        @Override
        public Set<String> getLoadedComponents() {
            return Set.of();
        }

        @Override
        public Map<String, Object> invokeComponent(String componentId, Map<String, Object> params) {
            return Map.of();
        }

        @Override
        public Map<String, Object> testConnection() {
            return Map.of();
        }

        @Override
        public void unloadComponent(String componentId) {
        }
    }
}
