package org.leo.ai.runtime;

import java.util.Objects;

/**
 * AI 执行引擎输出的传输层无关事件。
 *
 * <p>事件只描述单轮执行中发生了什么；SSE、WebSocket 或测试适配器负责决定如何下发。
 */
public record AiTurnEvent(Type type, Object data) {

    public AiTurnEvent {
        Objects.requireNonNull(type, "type");
    }

    public enum Type {
        THINKING_DELTA,
        TEXT_DELTA,
        TOOL_CALL_DELTA,
        TOOL_STARTED,
        TOOL_COMPLETED
    }

    public static AiTurnEvent thinkingDelta(String text) {
        return new AiTurnEvent(Type.THINKING_DELTA, text);
    }

    public static AiTurnEvent textDelta(String text) {
        return new AiTurnEvent(Type.TEXT_DELTA, text);
    }

    public static AiTurnEvent toolCallDelta(Object data) {
        return new AiTurnEvent(Type.TOOL_CALL_DELTA, data);
    }

    public static AiTurnEvent toolStarted(Object data) {
        return new AiTurnEvent(Type.TOOL_STARTED, data);
    }

    public static AiTurnEvent toolCompleted(Object data) {
        return new AiTurnEvent(Type.TOOL_COMPLETED, data);
    }
}
