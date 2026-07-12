package org.leo.web.controller.puppetnode.scan;


import org.leo.core.puppet.capability.ScanCapable;
import org.leo.core.util.ApiResponse;
import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;

@RestController
@RequestMapping("/puppet-node/port-scan")
public class PortScanController {

    @RequestMapping(value = "/start-scan", method = RequestMethod.POST)
    public HashMap<String, Object> startScan(@RequestBody HashMap<String, Object> params) {
        try {
            String scanHost = ControllerUtil.getRequiredStringParam(params, "scanHost");
            int[] scanPorts = getPorts(params.get("scanPorts"));
            int scanTimeout = getIntInRange(params.get("scanTimeout"), "scanTimeout", 1, 300000);
            int threadsNum = getIntInRange(params.get("threadsNum"), "threadsNum", 1, 100);
            return ControllerUtil.handleCapabilityCall(params, ScanCapable.class, "启动端口扫描失败",
                    node -> node.startScanPort(scanHost, scanPorts, scanTimeout, threadsNum));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    /**
     * 查询端口扫描结果
     *
     * @param params 请求参数，包含：
     *               - sessionId: 会话ID（必需）
     *               - taskId: 扫描任务ID（必需）
     * @return 扫描任务信息和结果
     */
    @RequestMapping(value = "/query-result", method = RequestMethod.POST)
    public HashMap<String, Object> queryResult(@RequestBody HashMap<String, Object> params) {
        try {
            String taskId = ControllerUtil.getRequiredStringParam(params, "taskId");
            return ControllerUtil.handleCapabilityCall(params, ScanCapable.class, "查询端口扫描结果失败", node -> node.queryScanPortResult(taskId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    /**
     * 暂停端口扫描
     *
     * @param params 请求参数，包含：
     *               - sessionId: 会话ID（必需）
     *               - taskId: 扫描任务ID（必需）
     * @return 操作结果
     */
    @RequestMapping(value = "/pause-scan", method = RequestMethod.POST)
    public HashMap<String, Object> pauseScan(@RequestBody HashMap<String, Object> params) {
        try {
            String taskId = ControllerUtil.getRequiredStringParam(params, "taskId");
            return ControllerUtil.handleCapabilityCall(params, ScanCapable.class, "暂停端口扫描失败", node -> node.pauseScanPort(taskId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    /**
     * 继续端口扫描
     *
     * @param params 请求参数，包含：
     *               - sessionId: 会话ID（必需）
     *               - taskId: 扫描任务ID（必需）
     * @return 操作结果
     */
    @RequestMapping(value = "/resume-scan", method = RequestMethod.POST)
    public HashMap<String, Object> resumeScan(@RequestBody HashMap<String, Object> params) {
        try {
            String taskId = ControllerUtil.getRequiredStringParam(params, "taskId");
            return ControllerUtil.handleCapabilityCall(params, ScanCapable.class, "继续端口扫描失败", node -> node.resumeScanPort(taskId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    /**
     * 终止端口扫描
     *
     * @param params 请求参数，包含：
     *               - sessionId: 会话ID（必需）
     *               - taskId: 扫描任务ID（必需）
     * @return 操作结果
     */
    @RequestMapping(value = "/stop-scan", method = RequestMethod.POST)
    public HashMap<String, Object> stopScan(@RequestBody HashMap<String, Object> params) {
        try {
            String taskId = ControllerUtil.getRequiredStringParam(params, "taskId");
            return ControllerUtil.handleCapabilityCall(params, ScanCapable.class, "终止端口扫描失败", node -> node.stopScanPort(taskId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    private int[] getPorts(Object value) {
        if (!(value instanceof List<?> ports) || ports.isEmpty()) {
            throw new IllegalArgumentException("scanPorts必须是非空端口数组");
        }
        int[] parsed = new int[ports.size()];
        for (int i = 0; i < ports.size(); i++) {
            parsed[i] = getIntInRange(ports.get(i), "scanPorts[" + i + "]", 1, 65535);
        }
        return parsed;
    }

    private int getIntInRange(Object value, String fieldName, int min, int max) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + "不能为空");
        }
        final int parsed;
        try {
            parsed = value instanceof Number number
                    ? number.intValue()
                    : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(fieldName + "必须是整数", e);
        }
        if (parsed < min || parsed > max) {
            throw new IllegalArgumentException(fieldName + "必须在" + min + "到" + max + "之间");
        }
        return parsed;
    }
}
