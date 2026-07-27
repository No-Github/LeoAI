package org.leo.core.entity;

/** 过期线程租约下仍处于 running 的 Run，用于异常实例收口。 */
public class AiOrphanedRunRecord {
    private String threadId;
    private String turnId;
    private String runId;
    private String assistantMessageId;
    private Long startedAt;

    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }
    public String getTurnId() { return turnId; }
    public void setTurnId(String turnId) { this.turnId = turnId; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getAssistantMessageId() { return assistantMessageId; }
    public void setAssistantMessageId(String assistantMessageId) {
        this.assistantMessageId = assistantMessageId;
    }
    public Long getStartedAt() { return startedAt; }
    public void setStartedAt(Long startedAt) { this.startedAt = startedAt; }
}
