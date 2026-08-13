package org.leo.ai.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.leo.ai.service.AiOperationAssessmentService;
import org.leo.ai.service.AiUserInputService;
import org.leo.ai.thread.AiConversationStoreService;
import org.leo.core.entity.AiUserInputRequest;
import org.springframework.stereotype.Component;

/**
 * Single execution gate for assessed business mutations and user confirmation.
 * Tool authorization owns identity; this class owns the operation protocol only.
 */
@Component
public class AiOperationGate {

    private final AiOperationAssessmentService assessments;
    private final AiConversationStoreService conversations;

    public AiOperationGate(AiOperationAssessmentService assessments,
                           AiConversationStoreService conversations) {
        this.assessments = assessments;
        this.conversations = conversations;
    }

    public void authorize(Object memoryId, AiToolDescriptor descriptor,
                          ToolExecutionRequest request, String confirmationRequestId) {
        if (descriptor.operation() == AiToolOperation.READ_ONLY || !descriptor.business()) return;
        String toolName = descriptor.name();
        String arguments = request != null && request.arguments() != null
                ? request.arguments() : "{}";
        AiOperationAssessmentService.Assessment assessment =
                assessments.find(memoryId, toolName, arguments);
        if (assessment == null) throw assessmentRequired(toolName);
        if (!assessment.requiresConfirmation()) {
            consumeAssessment(assessment);
            return;
        }

        String threadId = AiToolContext.getThreadId();
        if (threadId == null || threadId.isBlank()) threadId = String.valueOf(memoryId);
        String hash = AiUserInputService.confirmationArgumentsHash(arguments);
        if (conversations == null || confirmationRequestId == null
                || confirmationRequestId.isBlank()) {
            throw confirmationRequired(toolName);
        }
        AiUserInputRequest confirmation = confirmationRequestId == null
                ? null : conversations.findUserInputRequest(confirmationRequestId);
        if (confirmation == null
                || !threadId.equals(confirmation.getThreadId())
                || !AiUserInputRequest.TYPE_CONFIRMATION.equals(confirmation.getRequestType())
                || !AiUserInputRequest.STATUS_ANSWERED.equals(confirmation.getStatus())
                || !AiUserInputService.isAffirmativeAnswer(confirmation.getAnswer())
                || !toolName.equals(confirmation.getToolName())
                || !java.util.Objects.equals(hash, confirmation.getArgumentsHash())
                || !conversations.consumeConfirmation(confirmationRequestId, threadId,
                        toolName, hash, System.currentTimeMillis())) {
            throw confirmationRequired(toolName);
        }
        consumeAssessment(assessment);
    }

    private void consumeAssessment(AiOperationAssessmentService.Assessment assessment) {
        if (!assessments.consume(assessment)) {
            throw AiToolException.modelCorrectable(
                    "OPERATION_ASSESSMENT_ALREADY_USED",
                    "本次操作评估已被使用或已过期。",
                    "重新评估当前准确参数并再次请求用户确认。");
        }
    }

    private AiToolException assessmentRequired(String toolName) {
        return AiToolException.modelCorrectable(
                "OPERATION_ASSESSMENT_REQUIRED",
                "业务变更工具 " + toolName + " 必须先调用 assess_operation 评估本次具体操作。",
                "先调用 assess_operation，传入准确 toolName、完整 argumentsJson、riskLevel、"
                        + "requiresConfirmation 和风险原因；读取类操作不要调用该工具。");
    }

    private AiToolException confirmationRequired(String toolName) {
        return AiToolException.userActionRequired(
                "USER_CONFIRMATION_REQUIRED",
                "执行高风险工具 " + toolName + " 前需要用户明确确认。",
                "调用 request_user_input(type=CONFIRMATION) 绑定准确工具名和完整参数，"
                        + "等待用户选择确认后再执行；不要自行假设用户同意。");
    }
}
