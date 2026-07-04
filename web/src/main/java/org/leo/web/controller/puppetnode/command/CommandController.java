package org.leo.web.controller.puppetnode.command;

import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.core.puppet.capability.TerminalCapable;
import org.leo.core.util.ApiResponse;
import org.leo.web.dto.puppetnode.command.CommandExecRequest;
import org.leo.web.exception.ApiException;
import org.leo.web.util.AuditLogUtil;
import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/puppet-node/command")
public class CommandController {

    @PostMapping("/exec-command")
    public Map<String, Object> execCommand(@RequestBody CommandExecRequest request) {
        AbstractPuppetNode auditNode = null;
        String cmd = request == null ? null : request.cmd();
        Map<String, Object> auditParams = auditParams(request);
        try {
            if (request == null) {
                throw ApiException.badRequest("请求体不能为空");
            }
            String sessionId = requireText(request.sessionId(), "sessionId");
            String type = requireCommandType(request.type());
            String processId = requireText(request.processId(), "processId");
            cmd = "write".equals(type) ? requireCommandPayload(request.cmd()) : normalizeCommand(request.cmd());

            TerminalCapable commandNode = ControllerUtil.requireCapability(sessionId, TerminalCapable.class);
            if (commandNode instanceof AbstractPuppetNode node) {
                auditNode = node;
            }
            Map<String, Object> results = commandNode.execCommand(type, cmd, processId);
            logCommandAuditSuccess(auditNode, type, cmd, processId, auditParams);
            return ApiResponse.success(results != null ? results : Collections.emptyMap());
        } catch (ApiException e) {
            logCommandAuditFailure(auditNode, request == null ? null : request.type(), cmd,
                    request == null ? null : request.processId(), auditParams, e.getMessage());
            throw e;
        } catch (Exception e) {
            logCommandAuditFailure(auditNode, request == null ? null : request.type(), cmd,
                    request == null ? null : request.processId(), auditParams, e.getMessage());
            throw ApiException.serverError("执行命令失败: " + e.getMessage());
        }
    }

    private String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(name + "不能为空");
        }
        return value.trim();
    }

    private String requireCommandType(String value) {
        String type = requireText(value, "type");
        if (!"write".equals(type) && !"read".equals(type) && !"stop".equals(type)) {
            throw ApiException.badRequest("type不支持");
        }
        return type;
    }

    private String requireCommandPayload(String value) {
        if (value == null || value.isEmpty()) {
            throw ApiException.badRequest("cmd不能为空");
        }
        return value;
    }

    private String normalizeCommand(String value) {
        return value == null ? "" : value;
    }

    private Map<String, Object> auditParams(CommandExecRequest request) {
        Map<String, Object> params = new HashMap<>();
        if (request == null) {
            return params;
        }
        params.put("sessionId", request.sessionId());
        params.put("cmd", request.cmd());
        params.put("type", request.type());
        params.put("processId", request.processId());
        return params;
    }

    private void logCommandAuditSuccess(AbstractPuppetNode node,
                                        String type,
                                        String cmd,
                                        String processId,
                                        Map<String, Object> auditParams) {
        if (node == null) {
            return;
        }
        if ("read".equals(type)) {
            return;
        }
        if ("stop".equals(type)) {
            AuditLogUtil.logSuccess(node, "COMMAND_STOP", "停止命令进程", processId, auditParams,
                    ApiResponse.CODE_SUCCESS, "停止命令进程成功", AuditLogUtil.getClientIp());
            return;
        }
        AuditLogUtil.logSuccess(node, "COMMAND_EXEC", "执行命令", cmd, auditParams,
                ApiResponse.CODE_SUCCESS, "执行命令成功", AuditLogUtil.getClientIp());
    }

    private void logCommandAuditFailure(AbstractPuppetNode node,
                                        String type,
                                        String cmd,
                                        String processId,
                                        Map<String, Object> auditParams,
                                        String errorMessage) {
        if (node == null) {
            return;
        }
        if ("read".equals(type)) {
            return;
        }
        if ("stop".equals(type)) {
            AuditLogUtil.logFailure(node, "COMMAND_STOP", "停止命令进程", processId, auditParams,
                    errorMessage, AuditLogUtil.getClientIp());
            return;
        }
        AuditLogUtil.logFailure(node, "COMMAND_EXEC", "执行命令", cmd, auditParams,
                errorMessage, AuditLogUtil.getClientIp());
    }
}
