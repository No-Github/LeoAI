package org.leo.core.disguise;

import org.leo.core.entity.Disguise;
import org.leo.core.runtime.PuppetRuntime;

import java.util.Map;

/** Runtime extension point for validating user supplied disguise implementations. */
public interface DisguiseRuntimeValidator {

    PuppetRuntime getRuntime();

    /**
     * Validate a runtime implementation without mutating the disguise.
     * Implementations should return diagnostics suitable for API/UI display.
     */
    Map<String, Object> validate(Disguise disguise) throws Exception;
}
