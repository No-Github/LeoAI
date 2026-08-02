package org.leo.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Docker 隔离脚本执行参数；不允许回退到宿主机 Shell。 */
@Component
@ConfigurationProperties(prefix = "leo.ai.sandbox")
public class AgentSandboxProperties {

    private boolean enabled;
    private String dockerCommand = "docker";
    private String pythonImage = "python:3.12-alpine";
    private String nodeImage = "node:22-alpine";
    private String javaImage = "eclipse-temurin:17-jdk-alpine";
    private long timeoutMs = 120_000L;
    private String memory = "256m";
    private String cpus = "1";
    private int pidsLimit = 64;
    private long maxLogBytes = 2L * 1024 * 1024;

    public boolean isEnabled() { return enabled; }
    public String getDockerCommand() { return dockerCommand; }
    public String getPythonImage() { return pythonImage; }
    public String getNodeImage() { return nodeImage; }
    public String getJavaImage() { return javaImage; }
    public long getTimeoutMs() { return timeoutMs; }
    public String getMemory() { return memory; }
    public String getCpus() { return cpus; }
    public int getPidsLimit() { return pidsLimit; }
    public long getMaxLogBytes() { return maxLogBytes; }

    public void setEnabled(boolean value) { enabled = value; }
    public void setDockerCommand(String value) { dockerCommand = require(value, "dockerCommand"); }
    public void setPythonImage(String value) { pythonImage = require(value, "pythonImage"); }
    public void setNodeImage(String value) { nodeImage = require(value, "nodeImage"); }
    public void setJavaImage(String value) { javaImage = require(value, "javaImage"); }
    public void setTimeoutMs(long value) { timeoutMs = Math.max(1_000L, value); }
    public void setMemory(String value) { memory = require(value, "memory"); }
    public void setCpus(String value) { cpus = require(value, "cpus"); }
    public void setPidsLimit(int value) { pidsLimit = Math.max(16, value); }
    public void setMaxLogBytes(long value) { maxLogBytes = Math.max(16_384L, value); }

    private String require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " 不能为空");
        return value.trim();
    }
}
