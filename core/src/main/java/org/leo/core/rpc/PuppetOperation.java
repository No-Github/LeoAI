package org.leo.core.rpc;

/** Runtime-neutral Puppet execution operations. */
public enum PuppetOperation {
    PING,
    RELAY,
    COMPONENT_LOAD,
    COMPONENT_INVOKE,
    COMPONENT_REMOVE,
    PLUGIN_INVOKE
}
