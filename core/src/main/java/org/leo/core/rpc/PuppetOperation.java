package org.leo.core.rpc;

/** Runtime-neutral Puppet RPC operations with mappings to the legacy M protocol. */
public enum PuppetOperation {
    PING(0),
    RELAY(1),
    COMPONENT_PUT(2),
    COMPONENT_INVOKE(3),
    COMPONENT_REMOVE(null),
    PLUGIN_INVOKE(null);

    private final Integer legacyMode;

    PuppetOperation(Integer legacyMode) {
        this.legacyMode = legacyMode;
    }

    public Integer getLegacyMode() {
        return legacyMode;
    }

    public static PuppetOperation fromLegacyMode(int mode) {
        for (PuppetOperation operation : values()) {
            if (operation.legacyMode != null && operation.legacyMode.intValue() == mode) {
                return operation;
            }
        }
        throw new IllegalArgumentException("不支持的 legacy Puppet mode: " + mode);
    }
}
