package org.leo.core.runtime;

import org.leo.core.entity.Puppet;
import org.leo.core.entity.User;
import org.leo.core.puppet.AbstractPuppetNode;

/** Equal-status runtime module SPI implemented by javacore and phpcore. */
public interface PuppetRuntimeModule {

    PuppetRuntime getRuntime();

    default boolean isReady() {
        return true;
    }

    AbstractPuppetNode createNode(Puppet puppet,
                                  User user,
                                  PuppetNodeCreationContext context) throws Exception;
}
