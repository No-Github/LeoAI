package org.leo.core.component.runtime;

import org.leo.core.runtime.PuppetRuntime;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/** Logical component metadata with runtime-specific artifact identifiers. */
public final class ComponentDescriptor {

    private final String id;
    private final String version;
    private final String capability;
    private final Map<PuppetRuntime, String> artifacts;

    public ComponentDescriptor(String id, String version, String capability,
                               Map<PuppetRuntime, String> artifacts) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("component id不能为空");
        }
        this.id = id.trim();
        this.version = version;
        this.capability = capability;
        EnumMap<PuppetRuntime, String> copy = new EnumMap<>(PuppetRuntime.class);
        if (artifacts != null) {
            copy.putAll(artifacts);
        }
        this.artifacts = Collections.unmodifiableMap(copy);
    }

    public String getId() {
        return id;
    }

    public String getVersion() {
        return version;
    }

    public String getCapability() {
        return capability;
    }

    public Map<PuppetRuntime, String> getArtifacts() {
        return artifacts;
    }

    public String artifactFor(PuppetRuntime runtime) {
        return artifacts.get(runtime);
    }
}
