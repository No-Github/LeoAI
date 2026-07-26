package org.leo.ai.runtime;

import java.util.Objects;

/** 统一描述模型失败或用户取消。 */
public record AiTurnFailure(Throwable cause, AiTurnOutcome outcome, String cancellationReason) {

    public AiTurnFailure {
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(outcome, "outcome");
        if (outcome == AiTurnOutcome.COMPLETED) {
            throw new IllegalArgumentException("failure outcome 不能为 COMPLETED");
        }
    }

    public boolean cancelled() {
        return outcome == AiTurnOutcome.CANCELLED;
    }
}
