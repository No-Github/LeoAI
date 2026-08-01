package org.leo.ai.tools.common;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.leo.ai.service.AiUserInputService;
import org.springframework.stereotype.Component;
import org.leo.core.entity.AiUserInputOption;

import java.util.List;
import java.util.Map;

/** Platform 与 Puppet Agent 共用的结构化提问工具。 */
@Component
public class UserInputTools {

    private final AiUserInputService service;

    public UserInputTools(AiUserInputService service) {
        this.service = service;
    }

    @Tool(name = "request_user_input", value = """
            当缺少会显著改变结果的用户意图，或执行高风险动作前需要明确确认时，
            创建结构化问题并暂停原任务。调用后不得继续调用其他工具，本轮应立即结束。
            能通过只读工具查明的信息、低风险且可逆的常规选择不要询问。
            CONFIRMATION 必须传入准确的 toolName 和完整 argumentsJson，参数改变后重新确认。
            """)
    public Map<String, Object> requestUserInput(
            @P("CLARIFICATION 或 CONFIRMATION") String type,
            @P("一个具体、可直接回答的问题") String prompt,
            @P("最多 4 个选项对象，每个对象包含 label（展示文本）、value（稳定提交值）和 intent（业务语义）；无法枚举时传空数组") List<AiUserInputOption> options,
            @P("是否允许在问题卡片内自定义输入；默认 false，只有无法枚举答案时才传 true") Boolean allowFreeText,
            @P("待执行动作及影响摘要；普通澄清可为空") String actionSummary,
            @P("确认后计划调用的准确工具名；普通澄清可为空") String toolName,
            @P("确认后计划调用的完整参数 JSON；普通澄清可为空") String argumentsJson,
            @P("LOW、MEDIUM、HIGH 或 CRITICAL") String risk,
            @P("有效期秒数；0 使用默认 24 小时") Long expiresInSeconds) {
        return service.request(type, prompt, options, allowFreeText,
                actionSummary, toolName, argumentsJson, risk, expiresInSeconds);
    }
}
