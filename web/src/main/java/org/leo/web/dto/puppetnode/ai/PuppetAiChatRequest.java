package org.leo.web.dto.puppetnode.ai;

import org.leo.web.dto.ai.AiFileAttachment;

import java.util.List;

public record PuppetAiChatRequest(String sessionId,
                                  String threadId,
                                  String message,
                                  Integer configId,
                                  String reasoningEffort,
                                  List<AiFileAttachment> attachments,
                                  String clientUserMessageId,
                                  String answerToQuestionId) {
    public PuppetAiChatRequest(String sessionId,
                               String threadId,
                               String message,
                               Integer configId,
                               String reasoningEffort,
                               List<AiFileAttachment> attachments) {
        this(sessionId, threadId, message, configId, reasoningEffort, attachments, null, null);
    }

    public PuppetAiChatRequest(String sessionId,
                               String threadId,
                               String message,
                               Integer configId,
                               String reasoningEffort,
                               List<AiFileAttachment> attachments,
                               String clientUserMessageId) {
        this(sessionId, threadId, message, configId, reasoningEffort,
                attachments, clientUserMessageId, null);
    }
}
