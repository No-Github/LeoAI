package org.leo.web.controller.puppetnode.plugin;

import org.leo.core.entity.Plugin;
import org.leo.core.manager.PluginManager;
import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.core.puppet.capability.JavaPluginCapable;
import org.leo.core.puppet.capability.PluginCapable;
import org.leo.core.runtime.PuppetRuntime;
import org.leo.core.util.ApiResponse;
import org.leo.core.util.json.JsonUtil;
import org.leo.javacore.plugin.JavaPluginService;
import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Runtime-neutral saved-plugin invocation endpoint. */
@RestController
@RequestMapping("/puppet-node/plugin")
public final class PluginController {

    private final JavaPluginService javaPluginService;

    public PluginController(JavaPluginService javaPluginService) {
        this.javaPluginService = javaPluginService;
    }

    @PostMapping("/invoke")
    public HashMap<String, Object> invoke(@RequestBody HashMap<String, Object> params) {
        try {
            String pluginId = ControllerUtil.getRequiredStringParam(params, "pluginId");
            AbstractPuppetNode node = ControllerUtil.getAbstractPuppetNode(params);
            Plugin plugin = PluginManager.getInstance().getPluginById(pluginId);
            if (plugin == null) return ApiResponse.notFound("插件不存在: " + pluginId);

            Map<String, Object> result;
            if (node.getRuntime() == PuppetRuntime.PHP) {
                if (!(node instanceof PluginCapable capable)) {
                    return ApiResponse.badRequest("当前 PHP 节点缺少 plugin capability");
                }
                if (!"php".equalsIgnoreCase(plugin.resolveRuntime())) {
                    return ApiResponse.badRequest("插件运行时与当前节点不匹配: " + plugin.resolveRuntime());
                }
                byte[] payload = plugin.getBytecode();
                if (payload == null || payload.length == 0) {
                    return ApiResponse.badRequest("PHP 插件源码为空: " + pluginId);
                }
                result = capable.invokePlugin(pluginId, new String(payload, StandardCharsets.UTF_8),
                        parsePluginParams(params.get("pluginParam")));
            } else {
                if (!(node instanceof JavaPluginCapable capable)) {
                    return ApiResponse.badRequest("当前节点缺少 javaPlugin capability");
                }
                Object raw = params.get("pluginParam");
                String pluginParam = raw == null ? null
                        : raw instanceof String ? (String) raw : JsonUtil.toJsonString(raw);
                result = javaPluginService.invokePlugin(capable, pluginId, pluginParam);
            }
            return ApiResponse.success(result);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("调用插件失败: " + e.getMessage());
        }
    }

    private Map<String, Object> parsePluginParams(Object raw) {
        if (raw == null || String.valueOf(raw).isBlank()) return new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        }
        Object parsed = JsonUtil.fromJsonString(String.valueOf(raw), HashMap.class);
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("pluginParam必须是JSON对象");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }
}
