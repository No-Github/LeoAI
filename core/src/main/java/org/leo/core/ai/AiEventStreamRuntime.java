package org.leo.core.ai;

import org.leo.core.entity.AiSseEvent;

import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 可重放 AI 事件流所需的统一运行时契约。
 *
 * <p>平台与节点运行时只保存事件状态；具体使用 SSE、WebSocket 或其他传输由上层适配器决定。
 */
public interface AiEventStreamRuntime extends AiTurnRuntime {

    LinkedBlockingQueue<AiSseEvent> getAiSseEventQueue();

    AiSseEvent recordSseEvent(String name, Object data);

    AiSseEvent recordSseEvent(String name, Object data, String subagentInvocationId);

    List<AiSseEvent> recentSseEventsAfter(long afterSeq, int limit);

    long getLastSseEventSeq();

    String getRunStatus();
}
