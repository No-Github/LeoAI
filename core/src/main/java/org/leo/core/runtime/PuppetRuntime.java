package org.leo.core.runtime;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/** Stable runtime identifiers used by nodes, components, plugins and generators. */
public enum PuppetRuntime {
    JAVA("java"),
    PHP("php"),
    UNKNOWN("unknown");

    private final String value;

    PuppetRuntime(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static PuppetRuntime from(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (PuppetRuntime runtime : values()) {
            if (runtime.value.equals(normalized)) {
                return runtime;
            }
        }
        return UNKNOWN;
    }
}
