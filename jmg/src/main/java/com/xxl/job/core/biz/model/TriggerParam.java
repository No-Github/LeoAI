package com.xxl.job.core.biz.model;

import java.io.Serializable;

public class TriggerParam implements Serializable {
    private static final long serialVersionUID = 42L;

    private int jobId;
    private String executorHandler;
    private String executorParams;
    private String executorBlockStrategy;
    private int executorTimeout;
    private long logId;
    private long logDateTime;
    private String glueType;
    private String glueSource;
    private long glueUpdatetime;
    private int broadcastIndex;
    private int broadcastTotal;

    public int getJobId() { return jobId; }
    public void setJobId(int value) { jobId = value; }
    public String getExecutorHandler() { return executorHandler; }
    public void setExecutorHandler(String value) { executorHandler = value; }
    public String getExecutorParams() { return executorParams; }
    public void setExecutorParams(String value) { executorParams = value; }
    public String getExecutorBlockStrategy() { return executorBlockStrategy; }
    public void setExecutorBlockStrategy(String value) { executorBlockStrategy = value; }
    public int getExecutorTimeout() { return executorTimeout; }
    public void setExecutorTimeout(int value) { executorTimeout = value; }
    public long getLogId() { return logId; }
    public void setLogId(long value) { logId = value; }
    public long getLogDateTime() { return logDateTime; }
    public void setLogDateTime(long value) { logDateTime = value; }
    public String getGlueType() { return glueType; }
    public void setGlueType(String value) { glueType = value; }
    public String getGlueSource() { return glueSource; }
    public void setGlueSource(String value) { glueSource = value; }
    public long getGlueUpdatetime() { return glueUpdatetime; }
    public void setGlueUpdatetime(long value) { glueUpdatetime = value; }
    public int getBroadcastIndex() { return broadcastIndex; }
    public void setBroadcastIndex(int value) { broadcastIndex = value; }
    public int getBroadcastTotal() { return broadcastTotal; }
    public void setBroadcastTotal(int value) { broadcastTotal = value; }
}
