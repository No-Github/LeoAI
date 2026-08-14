package org.leo.ai.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.leo.ai.service.AiUserInputService;
import org.leo.ai.thread.AiConversationStoreService;
import org.leo.core.entity.AiUserInputRequest;
import org.springframework.stereotype.Component;

/**
 * Single execution gate for business mutations and user confirmation.
 * Tool authorization owns identity; this class owns the operation protocol only.
 */
@Component
public class AiOperationGate {

    private final AiConversationStoreService conversations;

    public AiOperationGate(AiConversationStoreService conversations) {
        this.conversations = conversations;
    }

    public void authorize(Object memoryId, AiToolDescriptor descriptor,
                          ToolExecutionRequest request, String confirmationRequestId) {
        if (descriptor.operation() == AiToolOperation.READ_ONLY || !descriptor.business()) return;
        String toolName = descriptor.name();
        String arguments = request != null && request.arguments() != null
                ? request.arguments() : "{}";
        String threadId = AiToolContext.getThreadId();
        if (threadId == null || threadId.isBlank()) threadId = String.valueOf(memoryId);
        // 没有确认请求表示 AI 已判断为低风险，直接执行；高风险流程必须先创建确认卡片。
        if (confirmationRequestId == null || confirmationRequestId.isBlank()) return;
        String hash = AiUserInputService.confirmationArgumentsHash(arguments);
        if (conversations == null) {
            throw confirmationRequired(toolName);
        }
        AiUserInputRequest confirmation = conversations.findUserInputRequest(confirmationRequestId);
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
    }

    private AiToolException confirmationRequired(String toolName) {
        return AiToolException.userActionRequired(
                "USER_CONFIRMATION_REQUIRED",
                "执行高风险工具 " + toolName + " 前需要用户明确确认。",
                "调用 request_user_input(type=CONFIRMATION) 绑定准确工具名和完整参数，"
                        + "等待用户选择确认后再执行；不要自行假设用户同意。");
    }
}
