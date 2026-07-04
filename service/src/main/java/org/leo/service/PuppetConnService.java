package org.leo.service;

import org.leo.core.entity.Puppet;
import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.service.puppetnode.PuppetNodeFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Puppet 连接构建与测试服务。
 *
 * <p>将原本散落在 {@code PuppetNodeController} 中的连接构建逻辑下沉到服务层，
 * 供 Web 层 Controller 和 AI 工具层共用，避免重复实现。
 */
@Service
public class PuppetConnService {

    private final PuppetService puppetService;
    private final PuppetNodeFactory puppetNodeFactory;

    public PuppetConnService(PuppetService puppetService,
                             PuppetNodeFactory puppetNodeFactory) {
        this.puppetService = puppetService;
        this.puppetNodeFactory = puppetNodeFactory;
    }

    // ── 公开 API ─────────────────────────────────────────────────────────────────

    /**
     * 仅测试连通性，不创建 Session。
     *
     * @param puppetId 目标 Puppet ID
     * @return 包含 {@code success}、{@code hostId}、{@code components}、{@code latencyMs} 的结果；
     *         失败时包含 {@code message}
     */
    public Map<String, Object> testConnection(String puppetId) {
        Puppet puppet = puppetService.findPuppetById(puppetId);
        if (puppet == null) {
            return fail("Puppet 不存在，puppetId: " + puppetId, null);
        }

        try {
            AbstractPuppetNode node = puppetNodeFactory.createLiveNode(puppet, null);

            long start = System.currentTimeMillis();
            Map<String, Object> result = node.testConnection();
            long latency = System.currentTimeMillis() - start;

            if (!isConnectionSuccess(result)) {
                Map<String, Object> data = new HashMap<>();
                data.put("success",   false);
                data.put("latencyMs", latency);
                data.put("message",   result != null ? String.valueOf(result.get("msg")) : "无响应");
                return data;
            }

            Map<String, Object> data = new HashMap<>();
            data.put("success",    true);
            data.put("hostId",     result.get("hostId"));
            data.put("components", result.get("components"));
            data.put("latencyMs",  latency);

            // 测试连接成功，记录心跳时间
            puppetService.updateLastHeartbeat(puppetId);

            return data;

        } catch (Exception e) {
            return fail(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(), null);
        }
    }

    private static Map<String, Object> fail(String message, Long latencyMs) {
        HashMap<String, Object> data = new HashMap<>();
        data.put("success", false);
        data.put("message", message);
        if (latencyMs != null) data.put("latencyMs", latencyMs);
        return data;
    }

    private static boolean isConnectionSuccess(Map<String, Object> result) {
        if (result == null) {
            return false;
        }
        Object code = result.get("code");
        if (code instanceof Number number) {
            return number.intValue() == 200;
        }
        return "200".equals(String.valueOf(code));
    }

}
