package org.leo.ai.tools.common;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.leo.ai.agent.AiToolContext;
import org.leo.ai.agent.AiToolKind;
import org.leo.ai.agent.AiToolOperation;
import org.leo.ai.agent.AiToolPolicy;
import org.leo.ai.service.AiOperationAssessmentService;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Records the Agent's semantic risk decision before a business mutation. */
@Component
@AiToolPolicy(kind = AiToolKind.CONTROL, operation = AiToolOperation.WRITE,
        exclusive = true, business = false)
public class OperationAssessmentTools {
    private final AiOperationAssessmentService service;

    public OperationAssessmentTools(AiOperationAssessmentService service) {
        this.service = service;
    }

    @Tool(name = "assess_operation", value = """
            调用会改变平台或 Puppet 状态的业务工具前，评估这一次具体操作。
            读取文件、读取凭据、ls/whoami/cat 等不改变目标状态的操作不需要评估。
            修改配置、删除已有文件、修改已有密码、停止服务、写数据库、上传文件、隧道和批量变更等，
            如果可能造成权限丢失、服务不可用、数据丢失或业务中断，必须 requiresConfirmation=true。
            传入准确工具名和完整参数 JSON；参数变化必须重新评估。
            """)
    public Map<String, Object> assessOperation(
            @P("计划调用的准确业务工具名") String toolName,
            @P("计划调用的完整参数 JSON") String argumentsJson,
            @P("LOW、MEDIUM、HIGH 或 CRITICAL") String riskLevel,
            @P("是否必须请求用户确认") boolean requiresConfirmation,
            @P("风险判断原因") String reason,
            @P(value = "可能影响", required = false) String impact,
            @P(value = "可行的回滚方式；无法可靠回滚时明确说明", required = false)
            String rollback) {
        return service.assess(AiToolContext.getSessionId(), toolName, argumentsJson,
                riskLevel, requiresConfirmation, reason, impact, rollback);
    }
}
