package org.leo.phpcore;

import org.leo.core.runtime.PuppetRuntime;
import org.leo.core.runtime.PuppetRuntimeModule;
import org.leo.core.runtime.PuppetNodeCreationContext;
import org.leo.core.entity.Puppet;
import org.leo.core.entity.User;
import org.leo.core.puppet.AbstractPuppetNode;
import org.springframework.stereotype.Component;

/**
 * PHP runtime integration module marker.
 *
 * <p>The module will contain the platform-side PHP node adapter, component
 * artifact resolver and target-side PHP templates. Keeping it separate from
 * {@code core} prevents runtime-specific source generation from leaking into
 * the shared protocol and capability contracts.
 */
@Component
public final class PhpCoreModule implements PuppetRuntimeModule {

    public static final PuppetRuntime RUNTIME = PuppetRuntime.PHP;

    public PhpCoreModule() {
    }

    @Override
    public PuppetRuntime getRuntime() {
        return RUNTIME;
    }

    @Override
    public boolean isReady() {
        return false;
    }

    @Override
    public AbstractPuppetNode createNode(Puppet puppet,
                                         User user,
                                         PuppetNodeCreationContext context) {
        throw new IllegalStateException("PHP runtime module尚未完成节点实现");
    }
}
