package org.leo.web.controller.puppetnode.scan;


import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.core.puppet.capability.ScanCapable;
import org.leo.core.util.ApiResponse;
import org.leo.web.exception.ApiException;
import org.leo.web.util.AuditLogUtil;
import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/puppet-node/host-reachable")
public class HostIsReachableController {
    @RequestMapping(value = "/scan", method = RequestMethod.POST)
    public HashMap<String, Object> scanReachableHost(@RequestBody HashMap<String, Object> params) {
        AbstractPuppetNode auditNode = null;
        String scanHostsStr = null;
        try {
            auditNode = ControllerUtil.getAbstractPuppetNode(params);
            ScanCapable scanNode = ControllerUtil.requireCapability(params, ScanCapable.class);

            // 获取必需参数
            ArrayList<String> scanHostsList = getScanHosts(params.get("scanHosts"));
            if (scanHostsList.isEmpty()) {
                throw new IllegalArgumentException("scanHosts参数不能为空");
            }
            // 构建主机列表字符串用于日志
            scanHostsStr = scanHostsList.toString();

            // 获取超时时间，默认3000毫秒
            Object timeoutObj = params.get("scanTimeout");
            int scanTimeout = timeoutObj == null ? 3000 : parsePositiveInt(timeoutObj, "scanTimeout");
            // 调用组件
            Map<String, Object> results = scanNode.scanReachableHost(scanHostsList, scanTimeout);
            if (results == null) {
                AuditLogUtil.logFailure(auditNode, "HOST_REACHABLE_SCAN", "主机可达性检测", scanHostsStr, params,
                        "组件调用返回结果为空", AuditLogUtil.getClientIp());
                return ApiResponse.error("主机可达性检测失败: 组件调用返回结果为空");
            }
            // 检查返回码
            Object code = results.get("code");
            if (code != null && !Integer.valueOf(200).equals(code)) {
                String errorMsg = results.get("msg") == null ? null : String.valueOf(results.get("msg"));
                return ApiResponse.error("主机可达性检测失败: " + errorMsg);
            }
            return ApiResponse.success(results);

        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            return ApiResponse.error("主机可达性检测失败: " + e.getMessage());
        }
    }

    private ArrayList<String> getScanHosts(Object value) {
        if (!(value instanceof java.util.List<?> values)) {
            throw new IllegalArgumentException("scanHosts必须是字符串数组");
        }
        ArrayList<String> hosts = new ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof String host) || host.isBlank()) {
                throw new IllegalArgumentException("scanHosts必须是非空字符串数组");
            }
            hosts.add(host.trim());
        }
        return hosts;
    }

    private int parsePositiveInt(Object value, String fieldName) {
        final int parsed;
        try {
            parsed = value instanceof Number number
                    ? number.intValue()
                    : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(fieldName + "必须是正整数", e);
        }
        if (parsed < 1 || parsed > 300000) {
            throw new IllegalArgumentException(fieldName + "必须在1到300000毫秒之间");
        }
        return parsed;
    }
}
