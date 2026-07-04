package org.leo.core.puppet.capability;

import java.util.Map;
import java.util.Set;

/**
 * Capability marker for nodes that can load and invoke dynamic components.
 */
public interface ComponentManageCapable extends ComponentInvokeCapable {

    Set<String> getLoadedComponents();

    Map<String, Object> loadComponent(String componentId) throws Exception;
}
