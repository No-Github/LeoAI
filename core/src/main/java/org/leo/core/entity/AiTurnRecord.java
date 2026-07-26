package org.leo.core.entity;

public class AiTurnRecord {

    private String turnId;
    private String threadId;
    private String status;
    private Long createdAt;
    private Long completedAt;

    public String getTurnId() { return turnId; }
    public void setTurnId(String turnId) { this.turnId = turnId; }

    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }

    public Long getCompletedAt() { return completedAt; }
    public void setCompletedAt(Long completedAt) { this.completedAt = completedAt; }
}
