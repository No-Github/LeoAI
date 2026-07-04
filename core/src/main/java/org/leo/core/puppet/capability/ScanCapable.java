package org.leo.core.puppet.capability;

import java.util.ArrayList;
import java.util.Map;

/**
 * Capability marker for nodes that can run network scan tasks.
 */
public interface ScanCapable {

    Map<String, Object> startScanPort(String scanHost, int[] scanPorts, int scanTimeout, int threadsNum) throws Exception;

    Map<String, Object> queryScanPortResult(String taskId) throws Exception;

    Map<String, Object> pauseScanPort(String taskId) throws Exception;

    Map<String, Object> resumeScanPort(String taskId) throws Exception;

    Map<String, Object> stopScanPort(String taskId) throws Exception;

    Map<String, Object> scanReachableHost(ArrayList<String> scanHostsList, int scanTimeout) throws Exception;
}
