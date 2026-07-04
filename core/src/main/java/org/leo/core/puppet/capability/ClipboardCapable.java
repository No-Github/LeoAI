package org.leo.core.puppet.capability;

import java.util.Map;

/**
 * Capability marker for nodes that can operate on the remote clipboard.
 */
public interface ClipboardCapable {

    Map<String, Object> readClipboard() throws Exception;

    Map<String, Object> writeClipboard(String content) throws Exception;

    Map<String, Object> monitorClipboard(int duration, int interval) throws Exception;
}
