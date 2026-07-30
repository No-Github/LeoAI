package org.leo.service.transfer;

/**
 * Shared lifecycle for server-side file transfer tasks.
 *
 * <p>Only NEW and PAUSED may enter RUNNING. Terminal states are immutable;
 * retry is an explicit operation that first resets a FAILED task to NEW.</p>
 */
public enum TransferTaskState {
    NEW,
    RUNNING,
    PAUSED,
    CANCELLED,
    FAILED,
    COMPLETED;

    public boolean isTerminal() {
        return this == CANCELLED || this == FAILED || this == COMPLETED;
    }

    public boolean isActive() {
        return this == RUNNING;
    }

    public boolean canStart() {
        return this == NEW || this == PAUSED;
    }
}
