package org.leo.core.entity;

/** 持久化的 AI 线程事件。eventSeq 在同一 threadId 内单调递增。 */
public class AiEventRecord {

    private String eventId;
    private String runId;
    private String threadId;
    private String turnId;
    private String itemId;
    private String subagentInvocationId;
    private Long eventSeq;
    private Long timestamp;
    private String name;
    private String dataJson;

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }
    public String getTurnId() { return turnId; }
    public void setTurnId(String turnId) { this.turnId = turnId; }
    public String getItemId() { return itemId; }
    public void setItemId(String itemId) { this.itemId = itemId; }
    public String getSubagentInvocationId() { return subagentInvocationId; }
    public void setSubagentInvocationId(String subagentInvocationId) {
        this.subagentInvocationId = subagentInvocationId;
    }
    public Long getEventSeq() { return eventSeq; }
    public void setEventSeq(Long eventSeq) { this.eventSeq = eventSeq; }
    public Long getTimestamp() { return timestamp; }
    public void setTimestamp(Long timestamp) { this.timestamp = timestamp; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDataJson() { return dataJson; }
    public void setDataJson(String dataJson) { this.dataJson = dataJson; }
}
