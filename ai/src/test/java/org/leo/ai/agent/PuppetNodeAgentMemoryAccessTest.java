package org.leo.ai.agent;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

class PuppetNodeAgentMemoryAccessTest {

    @Test
    void exposesLangChainMemoryAccessForCancellationRepair() {
        PuppetNodeAgent agent = AiServices.builder(PuppetNodeAgent.class)
                .streamingChatModel(mock(StreamingChatModel.class))
                .chatModel(mock(ChatModel.class))
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(20)
                        .build())
                .build();

        assertNull(agent.getChatMemory("thread-1"));
        assertFalse(agent.evictChatMemory("thread-1"));
    }

    @Test
    void platformAgentExposesTheSameManagedMemoryContract() {
        PlatformAgent agent = AiServices.builder(PlatformAgent.class)
                .streamingChatModel(mock(StreamingChatModel.class))
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(20)
                        .build())
                .build();

        assertNull(agent.getChatMemory("thread-1"));
        assertFalse(agent.evictChatMemory("thread-1"));
    }
}
