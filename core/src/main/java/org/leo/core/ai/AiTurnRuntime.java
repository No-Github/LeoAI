package org.leo.core.ai;

/**
 * 单次 AI 对话执行所需的最小运行时状态契约。
 *
 * <p>该接口不包含模型、工具、SSE 或持久化细节，只统一平台 AI 与节点 AI
 * 的执行权、取消信号和终态转换。
 */
public interface AiTurnRuntime {

    boolean claimExecution();

    void markExecuting(Thread thread);

    void clearExecuting();

    boolean isStopRequested();

    String getStopReason();

    void setStopCallback(Runnable callback);

    void markCompleted();

    void markFailed();

    void markCancelled();
}
