package org.leo.ai.agent;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;

import java.util.ArrayList;
import java.util.List;

/**
 * 保证一次工具循环的最近用户消息始终出现在模型视图中。
 *
 * <p>LangChain4j 的动态工具提供器会在每轮工具执行后重新读取最近的
 * {@link UserMessage}。长工具链可能先触发滑动窗口淘汰用户消息，随后在
 * {@code UserMessage.findLast(...)} 处产生空 Optional。该包装器只修复模型视图，
 * 底层窗口仍按原有 token/消息上限淘汰历史和成对工具消息。
 */
final class ActiveUserPreservingChatMemory implements ChatMemory {

    private final ChatMemory delegate;
    private UserMessage activeUserMessage;

    ActiveUserPreservingChatMemory(ChatMemory delegate) {
        this.delegate = java.util.Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public Object id() {
        return delegate.id();
    }

    @Override
    public synchronized void add(ChatMessage message) {
        delegate.add(message);
        if (message instanceof UserMessage userMessage) {
            activeUserMessage = userMessage;
        }
    }

    @Override
    public synchronized List<ChatMessage> messages() {
        List<ChatMessage> messages = new ArrayList<>(delegate.messages());
        if (activeUserMessage == null || UserMessage.findLast(messages).isPresent()) {
            return messages;
        }

        int insertAt = 0;
        while (insertAt < messages.size()
                && messages.get(insertAt) instanceof SystemMessage) {
            insertAt++;
        }
        messages.add(insertAt, activeUserMessage);
        return messages;
    }

    @Override
    public synchronized void set(Iterable<ChatMessage> source) {
        List<ChatMessage> messages = new ArrayList<>();
        source.forEach(messages::add);
        delegate.set(messages);
        activeUserMessage = UserMessage.findLast(messages).orElse(null);
    }

    @Override
    public synchronized void clear() {
        delegate.clear();
        activeUserMessage = null;
    }
}
