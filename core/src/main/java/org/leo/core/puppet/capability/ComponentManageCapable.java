package org.leo.core.puppet.capability;

import java.util.Map;
import java.util.Collections;
import java.util.Set;

/**
 * Capability marker for nodes that can load and invoke dynamic components.
 */
public interface ComponentManageCapable extends ComponentInvokeCapable {

    Set<String> getLoadedComponents();

    /** Components that the platform can deploy to this runtime. */
    default Set<String> getAvailableComponents() {
        return Collections.emptySet();
    }

    Map<String, Object> loadComponent(String componentId) throws Exception;
}
