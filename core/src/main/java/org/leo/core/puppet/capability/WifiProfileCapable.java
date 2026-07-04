package org.leo.core.puppet.capability;

import java.util.Map;

/**
 * Capability marker for nodes that can inspect WiFi profiles and credentials.
 */
public interface WifiProfileCapable {

    Map<String, Object> listWifiProfiles() throws Exception;

    Map<String, Object> getWifiProfileDetail(String profileName) throws Exception;

    Map<String, Object> dumpAllWifiPasswords() throws Exception;
}
