package org.leo.core.ai;

/** Agent Turn 的统一运行状态。 */
public final class AiRunStatus {

    public static final String IDLE = "idle";
    public static final String RUNNING = "running";
    public static final String COMPLETED = "completed";
    public static final String FAILED = "failed";
    public static final String CANCELLED = "cancelled";
    public static final String WAITING_FOR_USER = "waiting_for_user";

    private AiRunStatus() {
    }
}
