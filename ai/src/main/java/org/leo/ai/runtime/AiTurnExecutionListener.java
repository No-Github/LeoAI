package org.leo.ai.runtime;

/**
 * 单轮执行场景适配器。
 *
 * <p>非终态事件用于 UI/日志输出，终态回调用于提交或丢弃 Turn。
 */
interface AiTurnExecutionListener {

    void onEvent(AiTurnEvent event);

    void onCompleted(AiTurnResult result) throws Exception;

    void onFailed(AiTurnFailure failure) throws Exception;

    /**
     * 终态动作本身失败时的最后恢复入口，例如数据库提交失败后重建 Memory。
     *
     * <p>该回调不得再次抛出异常。
     */
    default void onTerminalFailure(AiTurnOutcome attemptedOutcome, Exception error) {
    }
}
