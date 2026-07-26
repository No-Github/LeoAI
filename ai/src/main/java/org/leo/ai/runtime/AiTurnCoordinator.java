package org.leo.ai.runtime;

import org.leo.core.ai.AiTurnRuntime;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 统一平台 AI 与节点 AI 的单轮执行生命周期。
 *
 * <p>控制器负责抢占执行权，后台任务通过 {@link #attach(AiTurnRuntime)}
 * 绑定当前工作线程。完成、失败、取消以及同步启动异常最终都必须通过
 * {@link Execution#finish(AiTurnOutcome, TerminalAction)} 收口。
 */
@Component
public class AiTurnCoordinator {

    public boolean tryClaim(AiTurnRuntime runtime) {
        return Objects.requireNonNull(runtime, "runtime").claimExecution();
    }

    public void releaseClaim(AiTurnRuntime runtime) {
        if (runtime != null) {
            runtime.clearExecuting();
        }
    }

    public Execution attach(AiTurnRuntime runtime) {
        AiTurnRuntime required = Objects.requireNonNull(runtime, "runtime");
        required.markExecuting(Thread.currentThread());
        return new Execution(required);
    }

    @FunctionalInterface
    public interface TerminalAction {
        void run() throws Exception;
    }

    public static final class Execution {

        private static final String DEFAULT_CANCEL_REASON = "已停止";

        private final AiTurnRuntime runtime;
        private final AtomicBoolean finished = new AtomicBoolean(false);

        private Execution(AiTurnRuntime runtime) {
            this.runtime = runtime;
        }

        public boolean isCancellationRequested() {
            return runtime.isStopRequested();
        }

        public boolean isCancellation(Throwable error) {
            return runtime.isStopRequested() || hasInterruptedCause(error);
        }

        public String cancellationReason() {
            String reason = runtime.getStopReason();
            return reason != null && !reason.isBlank() ? reason : DEFAULT_CANCEL_REASON;
        }

        public void registerCancellation(Runnable callback) {
            runtime.setStopCallback(callback);
        }

        /**
         * 原子完成本轮执行。多个异步终止信号竞争时，只有第一个调用执行终态动作。
         *
         * @return 当前调用是否赢得终结权
         */
        public boolean finish(AiTurnOutcome outcome, TerminalAction action) throws Exception {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(action, "action");
            if (!finished.compareAndSet(false, true)) {
                return false;
            }
            markOutcome(outcome);
            try {
                action.run();
            } catch (Throwable error) {
                runtime.markFailed();
                if (error instanceof Exception exception) {
                    throw exception;
                }
                throw (Error) error;
            } finally {
                runtime.clearExecuting();
            }
            return true;
        }

        public boolean isFinished() {
            return finished.get();
        }

        private void markOutcome(AiTurnOutcome outcome) {
            switch (outcome) {
                case COMPLETED -> runtime.markCompleted();
                case FAILED -> runtime.markFailed();
                case CANCELLED -> runtime.markCancelled();
            }
        }

        private static boolean hasInterruptedCause(Throwable error) {
            Throwable current = error;
            while (current != null) {
                if (current instanceof InterruptedException) {
                    return true;
                }
                current = current.getCause();
            }
            return false;
        }
    }
}
