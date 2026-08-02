package org.leo.ai.runtime;

import dev.langchain4j.model.chat.response.ChatResponse;

/** 统一执行引擎产生的成功结果。 */
public record AiTurnResult(String output,
                           ChatResponse response,
                           long completedAt,
                           boolean userInputRequested) {

    public AiTurnResult {
        output = output != null ? output : "";
    }

    public AiTurnResult(String output, ChatResponse response, long completedAt) {
        this(output, response, completedAt, false);
    }
}
