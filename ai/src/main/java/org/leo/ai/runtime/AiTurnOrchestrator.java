package org.leo.ai.runtime;

import org.leo.ai.thread.AiConversationStoreService;
import org.leo.core.entity.AiRuntimeStats;
import org.leo.core.entity.AiSseEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 单轮 AI 执行的应用层编排器。
 *
 * <p>统一模型执行、Turn 持久化终态、启动失败和终态补偿。场景层只通过
 * {@link Lifecycle} 适配事件传输、运行时快照和最终响应。
 */
@Component
public class AiTurnOrchestrator {

    private static final Logger logger = LoggerFactory.getLogger(AiTurnOrchestrator.class);
    private final AiTurnExecutionEngine executionEngine;
    private final AiTurnTransaction turnTransaction;
    private final AiTurnTelemetryRegistry telemetryRegistry;

    public AiTurnOrchestrator(AiTurnExecutionEngine executionEngine,
                              AiTurnTransaction turnTransaction,
                              AiTurnTelemetryRegistry telemetryRegistry) {
        this.executionEngine = executionEngine;
        this.turnTransaction = turnTransaction;
        this.telemetryRegistry = telemetryRegistry;
    }

    public void execute(Request request, Lifecycle lifecycle) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(lifecycle, "lifecycle");

        AiTurnTransaction.Session transaction =
                turnTransaction.open(request.transactionContext());
        AiTurnTrace trace = transaction.trace();
        trace.checkpoint(AiTurnTrace.Checkpoint.ORCHESTRATION_STARTED);
        try {
            lifecycle.onStarted();
            trace.checkpoint(AiTurnTrace.Checkpoint.PRESENTATION_STARTED);
        } catch (Throwable error) {
            finishPreparationFailure(request, lifecycle, transaction, error);
            return;
        }

        trace.checkpoint(AiTurnTrace.Checkpoint.MODEL_STARTED);
        executionEngine.execute(request.command(), new AiTurnExecutionListener() {
            @Override
            public void onEvent(AiTurnEvent event) {
                trace.checkpoint(AiTurnTrace.Checkpoint.FIRST_EVENT);
                lifecycle.onEvent(event);
            }

            @Override
            public void onCompleted(AiTurnResult result) throws Exception {
                trace.checkpoint(AiTurnTrace.Checkpoint.MODEL_COMPLETED);
                lifecycle.beforeCommit();
                trace.checkpoint(AiTurnTrace.Checkpoint.PERSISTENCE_STARTED);
                AiTurnTransaction.CompletedTurn completed = transaction.commit(
                        result,
                        request.eventLog(),
                        request.planSnapshot().get(),
                        request.runtimeStats());
                trace.checkpoint(AiTurnTrace.Checkpoint.PERSISTENCE_COMPLETED);
                runAfterPersistence(
                        AiTurnOutcome.COMPLETED,
                        transaction,
                        lifecycle,
                        () -> {
                            lifecycle.onCommitted(completed);
                            trace.checkpoint(
                                    AiTurnTrace.Checkpoint.PRESENTATION_COMPLETED);
                            finishTrace(
                                    transaction, AiTurnOutcome.COMPLETED,
                                    null, null);
                        });
            }

            @Override
            public void onFailed(AiTurnFailure failure) throws Exception {
                trace.checkpoint(AiTurnTrace.Checkpoint.MODEL_FAILED);
                finalizeFailure(transaction, lifecycle, failure, true);
            }

            @Override
            public void onTerminalFailure(
                    AiTurnOutcome attemptedOutcome, Exception error) {
                recoverTerminalFailure(
                        transaction, lifecycle, attemptedOutcome, error);
            }
        });
    }

    private void finishPreparationFailure(Request request,
                                          Lifecycle lifecycle,
                                          AiTurnTransaction.Session transaction,
                                          Throwable error) {
        AiTurnCoordinator.Execution execution = request.command().execution();
        transaction.trace().checkpoint(
                AiTurnTrace.Checkpoint.PREPARATION_FAILED);
        AiTurnOutcome outcome = execution.isCancellation(error)
                ? AiTurnOutcome.CANCELLED : AiTurnOutcome.FAILED;
        AiTurnFailure failure = new AiTurnFailure(
                error,
                outcome,
                outcome == AiTurnOutcome.CANCELLED
                        ? execution.cancellationReason() : null);
        try {
            execution.finish(outcome, () ->
                    finalizeFailure(transaction, lifecycle, failure, false));
        } catch (Throwable terminalError) {
            recoverTerminalFailure(
                    transaction, lifecycle, outcome, asException(terminalError));
        }
    }

    private void finalizeFailure(AiTurnTransaction.Session transaction,
                                 Lifecycle lifecycle,
                                 AiTurnFailure failure,
                                 boolean modelStarted) throws Exception {
        lifecycle.beforeDiscard(failure);
        transaction.trace().checkpoint(
                AiTurnTrace.Checkpoint.PERSISTENCE_STARTED);
        AiTurnTransaction.FailedTurn failed = modelStarted
                ? transaction.discard(failure)
                : transaction.discardBeforeModel(failure);
        transaction.trace().checkpoint(
                AiTurnTrace.Checkpoint.PERSISTENCE_COMPLETED);
        runAfterPersistence(
                failure.outcome(),
                transaction,
                lifecycle,
                () -> {
                    lifecycle.onDiscarded(failed, failure);
                    transaction.trace().checkpoint(
                            AiTurnTrace.Checkpoint.PRESENTATION_COMPLETED);
                    finishTrace(
                            transaction,
                            failed.outcome(),
                            failed.cancelled()
                                    ? AiConversationStoreService.ERROR_CANCELLED
                                    : failed.classification().category(),
                            failed.message());
                });
    }

    /**
     * 持久化已经进入终态后，传输和响应适配失败不能再反向改变 Turn 结果。
     */
    private void runAfterPersistence(AiTurnOutcome outcome,
                                     AiTurnTransaction.Session transaction,
                                     Lifecycle lifecycle,
                                     TerminalAction action) {
        try {
            action.run();
        } catch (Throwable error) {
            recoverTerminalFailure(
                    transaction, lifecycle, outcome, asException(error));
        }
    }

    private void recoverTerminalFailure(AiTurnTransaction.Session transaction,
                                        Lifecycle lifecycle,
                                        AiTurnOutcome attemptedOutcome,
                                        Exception error) {
        boolean recovered = false;
        Exception recoveryError = null;
        transaction.trace().checkpoint(AiTurnTrace.Checkpoint.RECOVERY_STARTED);
        try {
            recovered = transaction.recoverTerminalFailure(
                    attemptedOutcome, error);
        } catch (Throwable terminalRecoveryError) {
            recoveryError = asException(terminalRecoveryError);
            logger.warn("AI Turn 持久化补偿失败: {}",
                    recoveryError.getMessage(), recoveryError);
        }
        Terminal terminal = new Terminal(
                attemptedOutcome,
                error,
                recovered,
                transaction.completedTurn(),
                transaction.failedTurn(),
                recoveryError);
        try {
            lifecycle.onTerminal(terminal);
        } catch (Throwable notificationError) {
            logger.warn("AI Turn 终态通知失败: {}",
                    notificationError.getMessage(), notificationError);
        } finally {
            transaction.trace().checkpoint(
                    AiTurnTrace.Checkpoint.RECOVERY_COMPLETED);
            AiTurnTransaction.FailedTurn failed = transaction.failedTurn();
            AiTurnOutcome finalOutcome = transaction.completedTurn() != null
                    ? AiTurnOutcome.COMPLETED
                    : failed != null ? failed.outcome() : AiTurnOutcome.FAILED;
            String category;
            String message;
            if (recoveryError != null) {
                category = AiConversationStoreService.ERROR_PERSISTENCE;
                message = recoveryError.getMessage();
            } else if (failed != null) {
                category = failed.cancelled()
                        ? AiConversationStoreService.ERROR_CANCELLED
                        : failed.classification().category();
                message = failed.message();
            } else if (transaction.completedTurn() != null) {
                category = "presentation";
                message = error.getMessage();
            } else {
                category = AiConversationStoreService.ERROR_PERSISTENCE;
                message = error.getMessage();
            }
            finishTrace(
                    transaction, finalOutcome, category, message);
        }
    }

    private void finishTrace(AiTurnTransaction.Session transaction,
                             AiTurnOutcome outcome,
                             String errorCategory,
                             String errorMessage) {
        AiTurnTrace trace = transaction.trace();
        trace.finish(outcome, errorCategory, errorMessage);
        transaction.persistTrace();
        telemetryRegistry.record(trace);
        logger.info(
                "AI Turn 完成 traceId={} runId={} conversationId={} outcome={} durationMs={} errorCategory={}",
                trace.traceId(), trace.runId(), trace.conversationId(),
                trace.outcome(), trace.durationMillis(), trace.errorCategory());
    }

    private static Exception asException(Throwable error) {
        return error instanceof Exception exception
                ? exception
                : new IllegalStateException("AI Turn 编排发生严重错误", error);
    }

    @FunctionalInterface
    private interface TerminalAction {
        void run() throws Exception;
    }

    public interface Lifecycle {

        default void onStarted() throws Exception {
        }

        void onEvent(AiTurnEvent event);

        default void beforeCommit() throws Exception {
        }

        void onCommitted(AiTurnTransaction.CompletedTurn completed) throws Exception;

        default void beforeDiscard(AiTurnFailure failure) throws Exception {
        }

        void onDiscarded(AiTurnTransaction.FailedTurn failed,
                         AiTurnFailure failure) throws Exception;

        default void onTerminal(Terminal terminal) {
        }
    }

    public record Request(AiTurnCommand command,
                          AiTurnTransaction.Context transactionContext,
                          List<AiSseEvent> eventLog,
                          Supplier<?> planSnapshot,
                          AiRuntimeStats runtimeStats) {

        public Request {
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(transactionContext, "transactionContext");
            eventLog = eventLog != null ? eventLog : List.of();
            planSnapshot = planSnapshot != null ? planSnapshot : () -> null;
        }
    }

    public record Terminal(AiTurnOutcome attemptedOutcome,
                           Exception error,
                           boolean recovered,
                           AiTurnTransaction.CompletedTurn completed,
                           AiTurnTransaction.FailedTurn failed,
                           Exception recoveryError) {

        public boolean committed() {
            return completed != null;
        }

        public boolean discarded() {
            return failed != null;
        }

        public boolean recoveryFailed() {
            return recoveryError != null;
        }
    }
}
