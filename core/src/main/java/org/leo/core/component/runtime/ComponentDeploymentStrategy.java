package org.leo.core.component.runtime;

import org.leo.core.runtime.PuppetRuntime;

import java.util.Map;

/** Runtime-specific component deployment without embedding deployment details in business services. */
public interface ComponentDeploymentStrategy {

    PuppetRuntime getRuntime();

    boolean supports(ComponentDeliveryMode deliveryMode);

    Map<String, Object> deploy(ComponentArtifact artifact) throws Exception;

    Map<String, Object> remove(String componentId) throws Exception;
}
