package org.leo.core.component.runtime;

/** How a runtime-specific component artifact becomes available on a target node. */
public enum ComponentDeliveryMode {
    BUNDLED,
    DYNAMIC,
    DISK_CACHE,
    INLINE
}
