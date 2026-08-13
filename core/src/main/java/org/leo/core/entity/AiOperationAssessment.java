package org.leo.core.entity;

/** Persisted one-shot semantic assessment for a concrete business tool call. */
public class AiOperationAssessment {
    private String assessmentId;
    private String userId;
    private String threadId;
    private String toolName;
    private String argumentsHash;
    private String riskLevel;
    private Boolean requiresConfirmation;
    private String reason;
    private String impact;
    private String rollback;
    private String status;
    private Long createdAt;
    private Long expiresAt;
    private Long consumedAt;

    public String getAssessmentId() { return assessmentId; }
    public void setAssessmentId(String value) { assessmentId = value; }
    public String getUserId() { return userId; }
    public void setUserId(String value) { userId = value; }
    public String getThreadId() { return threadId; }
    public void setThreadId(String value) { threadId = value; }
    public String getToolName() { return toolName; }
    public void setToolName(String value) { toolName = value; }
    public String getArgumentsHash() { return argumentsHash; }
    public void setArgumentsHash(String value) { argumentsHash = value; }
    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String value) { riskLevel = value; }
    public Boolean getRequiresConfirmation() { return requiresConfirmation; }
    public void setRequiresConfirmation(Boolean value) { requiresConfirmation = value; }
    public String getReason() { return reason; }
    public void setReason(String value) { reason = value; }
    public String getImpact() { return impact; }
    public void setImpact(String value) { impact = value; }
    public String getRollback() { return rollback; }
    public void setRollback(String value) { rollback = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long value) { createdAt = value; }
    public Long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Long value) { expiresAt = value; }
    public Long getConsumedAt() { return consumedAt; }
    public void setConsumedAt(Long value) { consumedAt = value; }
}
