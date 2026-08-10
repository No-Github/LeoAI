package org.leo.ai.agent;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.service.tool.AiServiceTool;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具元数据目录。目录由实际注册的工具对象构建，未知工具采用保守写操作语义。
 */
@Component
public class AiToolCatalog {

    private final Map<String, AiToolDescriptor> descriptors = new ConcurrentHashMap<>();

    public AiToolDescriptor register(Object source, AiServiceTool tool) {
        AiToolDescriptor descriptor = describe(source, tool.name());
        descriptors.merge(tool.name(), descriptor, AiToolCatalog::requireCompatible);
        return descriptor;
    }

    public AiToolDescriptor get(String toolName) {
        return descriptors.getOrDefault(toolName, AiToolDescriptor.conservative(toolName));
    }

    /**
     * 估算一组工具定义占用的上下文 token，用于给对话历史留下真实可用预算。
     * JSON 中同时包含英文结构和中文描述，按每两个字符约一个 token 保守估算。
     */
    public int estimateSchemaTokens(Collection<?> sources) {
        if (sources == null || sources.isEmpty()) return 0;
        long characters = 0;
        for (Object source : sources) {
            if (source == null) continue;
            for (AiServiceTool tool : dev.langchain4j.service.tool.ToolService.findTools(source)) {
                String json = tool.toolSpecification().toJson();
                characters += json != null ? json.length() : 0;
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, (characters + 1) / 2);
    }

    AiToolDescriptor describe(Object source, String toolName) {
        AiToolPolicy policy = findMethodPolicy(source, toolName);
        if (policy == null && source != null) {
            policy = source.getClass().getAnnotation(AiToolPolicy.class);
        }
        if (policy == null) return AiToolDescriptor.conservative(toolName);
        return new AiToolDescriptor(toolName, policy.kind(), policy.operation(),
                policy.terminal(), policy.exclusive(), policy.parallelizable(),
                policy.business());
    }

    private static AiToolPolicy findMethodPolicy(Object source, String toolName) {
        if (source == null || toolName == null) return null;
        for (Method method : source.getClass().getMethods()) {
            Tool tool = method.getAnnotation(Tool.class);
            if (tool == null) continue;
            String exposedName = tool.name() == null || tool.name().isBlank()
                    ? method.getName() : tool.name();
            if (toolName.equals(exposedName)) {
                return method.getAnnotation(AiToolPolicy.class);
            }
        }
        return null;
    }

    private static AiToolDescriptor requireCompatible(
            AiToolDescriptor existing, AiToolDescriptor candidate) {
        if (!existing.equals(candidate)) {
            throw new IllegalStateException("工具元数据冲突: " + existing.name());
        }
        return existing;
    }
}
