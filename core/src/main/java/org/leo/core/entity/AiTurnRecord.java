package org.leo.core.entity;

public class AiTurnRecord {

    private String turnId;
    private String threadId;
    private String status;
    private Long createdAt;
    private Long completedAt;
    private String protocolStatus;
    private String dispatchStatus;
    private String commandScope;
    private String commandJson;
    private String clientUserMessageId;
    private String userItemId;
    private String assistantItemId;
    private Long startedAt;
    private Boolean interruptRequested;
    private String errorMessage;
    private String answerToQuestionId;

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
    public String getProtocolStatus() { return protocolStatus; }
    public void setProtocolStatus(String protocolStatus) { this.protocolStatus = protocolStatus; }
    public String getDispatchStatus() { return dispatchStatus; }
    public void setDispatchStatus(String dispatchStatus) { this.dispatchStatus = dispatchStatus; }
    public String getCommandScope() { return commandScope; }
    public void setCommandScope(String commandScope) { this.commandScope = commandScope; }
    public String getCommandJson() { return commandJson; }
    public void setCommandJson(String commandJson) { this.commandJson = commandJson; }
    public String getClientUserMessageId() { return clientUserMessageId; }
    public void setClientUserMessageId(String clientUserMessageId) {
        this.clientUserMessageId = clientUserMessageId;
    }
    public String getUserItemId() { return userItemId; }
    public void setUserItemId(String userItemId) { this.userItemId = userItemId; }
    public String getAssistantItemId() { return assistantItemId; }
    public void setAssistantItemId(String assistantItemId) {
        this.assistantItemId = assistantItemId;
    }
    public Long getStartedAt() { return startedAt; }
    public void setStartedAt(Long startedAt) { this.startedAt = startedAt; }
    public Boolean getInterruptRequested() { return interruptRequested; }
    public void setInterruptRequested(Boolean interruptRequested) {
        this.interruptRequested = interruptRequested;
    }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getAnswerToQuestionId() { return answerToQuestionId; }
    public void setAnswerToQuestionId(String answerToQuestionId) {
        this.answerToQuestionId = answerToQuestionId;
    }
}
