package org.leo.web.dto.puppetnode.ai;

public record AiTurnInterruptRequest(String sessionId,
                                     String threadId,
                                     String turnId) {
}
