package org.leo.web.controller.puppetnode.service;

import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.core.puppet.capability.DiskCapable;
import org.leo.core.util.ApiResponse;
import org.leo.web.exception.ApiException;
import org.leo.web.util.AuditLogUtil;
import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 挂载磁盘枚举控制器
 */
@RestController
@RequestMapping("/puppet-node/mount-disk")
public class MountDiskController {

    @RequestMapping(value = "/list", method = RequestMethod.POST)
    public HashMap<String, Object> list(@RequestBody HashMap<String, Object> params) {
        AbstractPuppetNode auditNode = null;
        String clientIp = AuditLogUtil.getClientIp();
        try {
            DiskCapable node = ControllerUtil.requireCapability(params, DiskCapable.class);
            auditNode = ControllerUtil.getAbstractPuppetNode(params);
            Map<String, Object> result = node.listMountDisks();
            if (result == null) {
                String message = "获取磁盘信息失败: 返回为空";
                AuditLogUtil.logFailure(auditNode, "MOUNT_DISK_LIST", "获取磁盘信息",
                        "/puppet-node/mount-disk/list", params, message, clientIp);
                return ApiResponse.error(message);
            }
            Integer responseCode = extractResponseCode(result);
            String responseMessage = extractResponseMessage(result);
            if (responseCode == null || responseCode.intValue() != ApiResponse.CODE_SUCCESS) {
                String message = responseMessage == null || responseMessage.isBlank()
                        ? "获取磁盘信息失败: 失败"
                        : responseMessage;
                AuditLogUtil.logFailure(auditNode, "MOUNT_DISK_LIST", "获取磁盘信息",
                        "/puppet-node/mount-disk/list", params, message, clientIp);
                return ApiResponse.error(message);
            }
            HashMap<String, Object> response = ApiResponse.success(result);
            AuditLogUtil.logSuccess(auditNode, "MOUNT_DISK_LIST", "获取磁盘信息",
                    "/puppet-node/mount-disk/list", params, responseCode, responseMessage, clientIp);
            return response;
        } catch (ApiException e) {
            if (auditNode != null) {
                AuditLogUtil.logFailure(auditNode, "MOUNT_DISK_LIST", "获取磁盘信息",
                        "/puppet-node/mount-disk/list", params, e.getMessage(), clientIp);
            }
            throw e;
        } catch (Exception e) {
            if (auditNode != null) {
                AuditLogUtil.logError(auditNode, "MOUNT_DISK_LIST", "获取磁盘信息",
                        "/puppet-node/mount-disk/list", params, e.getMessage(), clientIp);
            }
            return ApiResponse.error("获取磁盘信息失败: " + e.getMessage());
        }
    }

    private Integer extractResponseCode(Map<String, Object> result) {
        Object codeObj = result.get("code");
        if (codeObj instanceof Number) {
            return ((Number) codeObj).intValue();
        }
        if (codeObj != null) {
            try {
                return Integer.valueOf(codeObj.toString());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private String extractResponseMessage(Map<String, Object> result) {
        Object msg = result.get("msg");
        return msg == null ? null : String.valueOf(msg);
    }
}
