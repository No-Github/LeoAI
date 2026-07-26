package org.leo.ai.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.service.memory.ChatMemoryAccess;
import org.leo.ai.thread.AiConversationStoreService.ConversationMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 以数据库中 committed 消息为事实来源管理 LangChain4j ChatMemory。
 */
@Component
public class ManagedConversationMemory {

    private final org.leo.ai.thread.AiConversationStoreService conversationStore;

    public ManagedConversationMemory(
            org.leo.ai.thread.AiConversationStoreService conversationStore) {
        this.conversationStore = conversationStore;
    }

    /**
     * 初始化新创建的 ChatMemory，并从数据库加载该会话的 committed 历史。
     */
    public ChatMemory initialize(ChatMemory memory) {
        if (memory == null) {
            return null;
        }
        replaceMessages(memory, toChatMessages(conversationStore.committedMessages(
                threadIdFromMemoryId(memory.id()), 200)));
        return memory;
    }

    /**
     * 使用已提交消息重建指定 Agent 的短期记忆。
     *
     * <p>取消或失败的 Turn 会被持久化为 discarded，因此不会重新进入模型上下文。
     */
    public boolean rebuild(ChatMemoryAccess agent, Object memoryId) {
        if (agent == null || memoryId == null) {
            return false;
        }
        ChatMemory memory = agent.getChatMemory(memoryId);
        if (memory == null) {
            return false;
        }
        List<ChatMessage> rebuilt = toChatMessages(conversationStore.committedMessages(
                threadIdFromMemoryId(memoryId), 200));
        replaceMessages(memory, rebuilt);
        return true;
    }

    /**
     * LangChain4j 1.16 的 {@link ChatMemory#set(List)} 不接受空列表。
     * 全新会话或失败 Turn 清理后没有 committed 历史时，空历史应表达为 clear。
     */
    private static void replaceMessages(ChatMemory memory, List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            memory.clear();
            return;
        }
        memory.set(messages);
    }

    static String threadIdFromMemoryId(Object memoryId) {
        String value = String.valueOf(memoryId);
        int separator = value.lastIndexOf(':');
        return separator >= 0 && separator < value.length() - 1
                ? value.substring(separator + 1)
                : value;
    }

    static List<ChatMessage> toChatMessages(List<ConversationMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<ChatMessage> result = new ArrayList<>();
        for (ConversationMessage message : messages) {
            if (message == null || message.content() == null || message.content().isBlank()) {
                continue;
            }
            if ("user".equalsIgnoreCase(message.role())) {
                result.add(UserMessage.from(message.content()));
            } else if ("assistant".equalsIgnoreCase(message.role())) {
                result.add(AiMessage.from(message.content()));
            }
        }
        return result;
    }
}
