package org.leo.core.entity;

/** 数据库中的 AI 线程执行租约。 */
public class AiThreadLeaseRecord {
    private String threadId;
    private String ownerId;
    private String leaseToken;
    private Long acquiredAt;
    private Long heartbeatAt;
    private Long expiresAt;

    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public String getLeaseToken() { return leaseToken; }
    public void setLeaseToken(String leaseToken) { this.leaseToken = leaseToken; }
    public Long getAcquiredAt() { return acquiredAt; }
    public void setAcquiredAt(Long acquiredAt) { this.acquiredAt = acquiredAt; }
    public Long getHeartbeatAt() { return heartbeatAt; }
    public void setHeartbeatAt(Long heartbeatAt) { this.heartbeatAt = heartbeatAt; }
    public Long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Long expiresAt) { this.expiresAt = expiresAt; }
}
