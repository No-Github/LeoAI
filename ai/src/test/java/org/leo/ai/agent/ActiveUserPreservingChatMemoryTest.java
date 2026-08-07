package org.leo.ai.agent;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActiveUserPreservingChatMemoryTest {

    @Test
    void restoresActiveUserToViewAfterDelegateEvictsIt() {
        ActiveUserPreservingChatMemory memory = memory(3);
        memory.add(new SystemMessage("system"));
        memory.add(UserMessage.from("继续当前任务"));
        memory.add(AiMessage.from("step-1"));
        memory.add(AiMessage.from("step-2"));
        memory.add(AiMessage.from("step-3"));

        List<ChatMessage> messages = memory.messages();

        assertEquals("system", ((SystemMessage) messages.get(0)).text());
        assertEquals("继续当前任务",
                UserMessage.findLast(messages).orElseThrow().singleText());
        assertEquals(1,
                messages.indexOf(UserMessage.findLast(messages).orElseThrow()));
    }

    @Test
    void replacesTrackedUserOnTheNextTurnAndClearsItWithMemory() {
        ActiveUserPreservingChatMemory memory = memory(2);
        memory.add(UserMessage.from("first"));
        memory.add(AiMessage.from("answer"));
        memory.add(UserMessage.from("second"));
        memory.add(AiMessage.from("step"));
        memory.add(AiMessage.from("step-2"));

        assertEquals("second",
                UserMessage.findLast(memory.messages()).orElseThrow().singleText());

        memory.clear();
        assertTrue(memory.messages().isEmpty());
    }

    private static ActiveUserPreservingChatMemory memory(int maxMessages) {
        return new ActiveUserPreservingChatMemory(
                MessageWindowChatMemory.builder()
                        .id("memory-1")
                        .maxMessages(maxMessages)
                        .build());
    }
}
