package org.leo.ai.runtime;

import dev.langchain4j.service.TokenStream;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 提交给统一执行引擎的单轮命令。
 *
 * <p>场景层负责准备 Agent 和持久化 Turn，引擎通过 streamFactory 延迟创建模型流，
 * 因而连 Agent 调用阶段的同步异常也能进入统一失败路径。
 */
public record AiTurnCommand(
        String conversationId,
        Object memoryId,
        AiTurnCoordinator.Execution execution,
        Supplier<TokenStream> streamFactory) {

    public AiTurnCommand {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId 不能为空");
        }
        Objects.requireNonNull(memoryId, "memoryId");
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(streamFactory, "streamFactory");
    }
}
