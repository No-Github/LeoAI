package org.leo.ai.tools.common;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.leo.ai.agent.AiToolContext;
import org.leo.ai.agent.AiToolKind;
import org.leo.ai.agent.AiToolOperation;
import org.leo.ai.agent.AiToolPolicy;
import org.leo.ai.service.AiOperationAssessmentService;

import java.util.Map;

/** Records the Agent's semantic risk decision before a business mutation. */
@AiToolPolicy(kind = AiToolKind.CONTROL, operation = AiToolOperation.WRITE,
        exclusive = true, business = false)
public class OperationAssessmentTools {
    private final AiOperationAssessmentService service;

    public OperationAssessmentTools(AiOperationAssessmentService service) {
        this.service = service;
    }

    @Tool(name = "assess_operation", value = """
            在调用受控业务工具之前，先由你评估这一次具体操作；这是执行前置步骤，不是执行失败后的补救步骤。
            明确标记为只读的专用工具不需要评估；exec、httpRequest 等可执行任意操作的通用入口始终先评估，
            即使本次只是 ls/whoami/cat 或 HTTP GET，也应评估为低风险、requiresConfirmation=false 后直接执行。
            修改配置、删除已有文件、修改已有密码、停止服务、写数据库、上传文件、隧道和批量变更等，
            如果可能造成权限丢失、服务不可用、数据丢失或业务中断，必须 requiresConfirmation=true。
            传入准确工具名和完整参数 JSON；参数变化必须重新评估。
            必须等待本工具返回后再调用目标工具，不要把评估和目标工具放在同一批并发调用中。
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
