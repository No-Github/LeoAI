package org.leo.core.puppet.capability;

/**
 * Internal capability for nodes whose service calls are scoped by a runtime host id.
 */
public interface HostScopedCapable {

    void setHostId(String hostId);
}
