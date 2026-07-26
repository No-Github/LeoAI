package org.leo.ai.runtime;

import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.service.TokenStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 平台 AI、节点 AI 和委派任务共用的流式 Turn 执行引擎。
 *
 * <p>负责回调注册、停止句柄竞态、工具事件标准化与唯一终态仲裁；
 * 不依赖 HTTP、SSE、Session 或具体 Agent 类型。
 */
@Component
public class AiTurnExecutionEngine {

    private static final Logger logger = LoggerFactory.getLogger(AiTurnExecutionEngine.class);

    void execute(AiTurnCommand command, AiTurnExecutionListener listener) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(listener, "listener");

        AiTurnCoordinator.Execution turn = command.execution();
        AtomicReference<StreamingHandle> handleRef = new AtomicReference<>();
        StringBuilder output = new StringBuilder();
        turn.registerCancellation(() -> cancelCaptured(handleRef));
        if (turn.isCancellationRequested()) {
            fail(turn, listener, new InterruptedException(turn.cancellationReason()));
            return;
        }

        try {
            TokenStream stream = Objects.requireNonNull(
                    command.streamFactory().get(), "streamFactory 返回了 null");
            stream
                    .onPartialThinkingWithContext((thinking, context) -> {
                        capture(handleRef, context.streamingHandle(), turn);
                        listener.onEvent(AiTurnEvent.thinkingDelta(thinking.text()));
                    })
                    .onPartialResponseWithContext((partial, context) -> {
                        capture(handleRef, context.streamingHandle(), turn);
                        if (partial.text() != null) {
                            output.append(partial.text());
                        }
                        listener.onEvent(AiTurnEvent.textDelta(partial.text()));
                    })
                    .onPartialToolCallWithContext((partial, context) -> {
                        capture(handleRef, context.streamingHandle(), turn);
                        listener.onEvent(AiTurnEvent.toolCallDelta(
                                AiToolEventNormalizer.partial(partial)));
                    })
                    .beforeToolExecution(execution -> {
                        rejectToolWhenCancelled(turn);
                        listener.onEvent(AiTurnEvent.toolStarted(
                                AiToolEventNormalizer.started(execution)));
                    })
                    .onToolExecuted(execution -> listener.onEvent(
                            AiTurnEvent.toolCompleted(AiToolEventNormalizer.completed(execution))))
                    .onCompleteResponse(response -> complete(
                            turn, listener, new AiTurnResult(
                                    output.toString(), response, System.currentTimeMillis())))
                    .onError(error -> fail(turn, listener, error))
                    .start();
        } catch (Throwable error) {
            fail(turn, listener, error);
        }
    }

    private void complete(AiTurnCoordinator.Execution turn,
                          AiTurnExecutionListener listener,
                          AiTurnResult result) {
        try {
            turn.finish(AiTurnOutcome.COMPLETED, () -> listener.onCompleted(result));
        } catch (Throwable error) {
            notifyTerminalFailure(listener, AiTurnOutcome.COMPLETED, asException(error));
        }
    }

    private void fail(AiTurnCoordinator.Execution turn,
                      AiTurnExecutionListener listener,
                      Throwable error) {
        if (turn.isFinished()) {
            return;
        }
        AiTurnOutcome outcome = turn.isCancellation(error)
                ? AiTurnOutcome.CANCELLED : AiTurnOutcome.FAILED;
        AiTurnFailure failure = new AiTurnFailure(
                error, outcome, outcome == AiTurnOutcome.CANCELLED
                        ? turn.cancellationReason() : null);
        try {
            turn.finish(outcome, () -> listener.onFailed(failure));
        } catch (Throwable terminalError) {
            notifyTerminalFailure(listener, outcome, asException(terminalError));
        }
    }

    private void notifyTerminalFailure(AiTurnExecutionListener listener,
                                       AiTurnOutcome attemptedOutcome,
                                       Exception error) {
        try {
            listener.onTerminalFailure(attemptedOutcome, error);
        } catch (Throwable recoveryError) {
            logger.warn("AI Turn 最终恢复动作失败: {}", recoveryError.getMessage(), recoveryError);
        }
    }

    private Exception asException(Throwable error) {
        return error instanceof Exception exception
                ? exception
                : new IllegalStateException("AI Turn 终态动作发生严重错误", error);
    }

    private void rejectToolWhenCancelled(AiTurnCoordinator.Execution turn) {
        if (turn.isCancellationRequested() || Thread.currentThread().isInterrupted()) {
            throw new RuntimeException(new InterruptedException(turn.cancellationReason()));
        }
    }

    private void capture(AtomicReference<StreamingHandle> handleRef,
                         StreamingHandle candidate,
                         AiTurnCoordinator.Execution turn) {
        if (candidate == null) return;
        handleRef.compareAndSet(null, candidate);
        StreamingHandle handle = handleRef.get();
        if (handle != null && turn.isCancellationRequested()) {
            handle.cancel();
        }
    }

    private void cancelCaptured(AtomicReference<StreamingHandle> handleRef) {
        StreamingHandle handle = handleRef.get();
        if (handle != null) {
            handle.cancel();
        }
    }
}
