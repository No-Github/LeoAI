package org.leo.ai.agent;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.model.TokenCountEstimator;
import org.leo.ai.config.AiAgentProperties;
import org.springframework.stereotype.Component;

/**
 * 按实际模型上下文窗口创建对话记忆，避免线程选模后仍沿用全局默认模型预算。
 */
@Component
public class AiChatMemoryProviderFactory {

    static final int RESERVED_CONTEXT_TOKENS = 20_000;
    static final int MIN_CONTEXT_TOKENS = 1_024;

    private final TokenCountEstimator tokenEstimator;
    private final ContextCompressionService compressionService;
    private final AiAgentProperties agentProperties;

    public AiChatMemoryProviderFactory(TokenCountEstimator tokenEstimator,
                                       ContextCompressionService compressionService,
                                       AiAgentProperties agentProperties) {
        this.tokenEstimator = tokenEstimator;
        this.compressionService = compressionService;
        this.agentProperties = agentProperties;
    }

    public ChatMemoryProvider createPuppetProvider(int modelContextWindowTokens) {
        return create(modelContextWindowTokens,
                agentProperties.getPuppetNode().getMain().getMaxContextTokens());
    }

    public ChatMemoryProvider createPlatformProvider(int modelContextWindowTokens) {
        return create(modelContextWindowTokens,
                agentProperties.getPlatform().getMain().getMaxContextTokens());
    }

    private ChatMemoryProvider create(int modelContextWindowTokens, int configuredMaxTokens) {
        int effectiveWindow = effectiveContextWindowTokens(modelContextWindowTokens, configuredMaxTokens);
        return memoryId -> {
            if (effectiveWindow <= 96_000) {
                return TokenWindowChatMemory.builder()
                        .id(memoryId)
                        .maxTokens(effectiveWindow, tokenEstimator)
                        .build();
            }
            int maxMessages = Math.max(50, effectiveWindow / 2_000);
            return new CompressingChatMemory(
                    memoryId,
                    MessageWindowChatMemory.builder().id(memoryId).maxMessages(maxMessages).build(),
                    compressionService,
                    effectiveWindow);
        };
    }

    /**
     * 模型窗口是硬上限，系统配置也是上限；两者不能通过 Math.max 被意外放大。
     */
    static int effectiveContextWindowTokens(int modelContextWindowTokens, int configuredMaxTokens) {
        int modelWindow = modelContextWindowTokens > 0 ? modelContextWindowTokens : 32_768;
        int available = Math.max(MIN_CONTEXT_TOKENS, modelWindow - RESERVED_CONTEXT_TOKENS);
        return configuredMaxTokens > 0 ? Math.min(available, configuredMaxTokens) : available;
    }
}
