package org.leo.ai.tools.puppetnode;

import org.leo.ai.agent.AiToolContext;
import org.leo.ai.util.PuppetNodeSessionUtils;
import org.leo.core.entity.Plugin;
import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.core.puppet.capability.JavaPluginCapable;
import org.leo.core.puppet.capability.PluginCapable;
import org.leo.core.runtime.PuppetRuntime;
import org.leo.javacore.plugin.JavaPluginService;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;

@Component
public class JavaPluginTools {

    private final JavaPluginService javaPluginService;

    public JavaPluginTools(JavaPluginService javaPluginService) {
        this.javaPluginService = javaPluginService;
    }

    @Tool("获取当前平台侧已加载的插件列表。适用于先查看可调用插件、pluginId、runtime、插件类型、参数示例和备注，再决定是否调用插件。这里查看的是平台侧插件元数据。")
    public ArrayList<Plugin> getJavaPlugins() {
        return javaPluginService.getAllPlugins();
    }

    @Tool("根据 pluginId 获取平台侧指定插件详情。适用于在调用前确认插件名称、runtime、插件类型、paramsDemo、版本和备注。")
    public Plugin getJavaPlugin(String pluginId) {
        return javaPluginService.getRequiredPlugin(pluginId);
    }

    @Tool("按 pluginType 获取平台侧插件列表。适用于插件较多时按类型筛选候选插件。")
    public ArrayList<Plugin> getJavaPluginsByType(String pluginType) {
        return javaPluginService.getPluginsByType(pluginType);
    }

    @Tool("调用与当前 puppet runtime 匹配的平台插件。插件由平台侧加载和管理，并绑定当前 session 对应的 puppet 执行。pluginId 必填；pluginParamJson 传 JSON 对象字符串，例如 {\"key\":\"value\"}。支持 Java 与 PHP runtime。")
    public Map<String, Object> invokeJavaPlugin(String pluginId, String pluginParamJson) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        AbstractPuppetNode node = PuppetNodeSessionUtils.getPuppetNode(sessionId);
        Plugin plugin = javaPluginService.getRequiredPlugin(pluginId);
        String runtime = plugin.resolveRuntime();
        if (!node.getRuntime().getValue().equalsIgnoreCase(runtime)) {
            throw new IllegalArgumentException("插件运行时与当前节点不匹配: " + runtime);
        }
        if (node.getRuntime() == PuppetRuntime.PHP) {
            PluginCapable capable = PuppetNodeSessionUtils.requireCapability(sessionId, PluginCapable.class);
            byte[] source = plugin.getBytecode();
            if (source == null || source.length == 0) {
                throw new IllegalArgumentException("PHP 插件源码为空: " + pluginId);
            }
            return capable.invokePlugin(pluginId, new String(source, StandardCharsets.UTF_8),
                    javaPluginService.parsePluginParam(pluginParamJson));
        }
        JavaPluginCapable capable = PuppetNodeSessionUtils.requireCapability(sessionId, JavaPluginCapable.class);
        return javaPluginService.invokePlugin(capable, pluginId, pluginParamJson);
    }
}
