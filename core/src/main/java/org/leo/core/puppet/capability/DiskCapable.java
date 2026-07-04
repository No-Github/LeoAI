package org.leo.core.puppet.capability;

import java.util.Map;

/**
 * Capability marker for nodes that can enumerate mounted disks.
 */
public interface DiskCapable {

    Map<String, Object> listMountDisks() throws Exception;
}
