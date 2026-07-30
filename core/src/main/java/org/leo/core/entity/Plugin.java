package org.leo.core.entity;

import org.leo.core.util.json.JsonUtil;

import java.util.LinkedHashMap;
import java.util.Map;

public class Plugin {

    private String pluginId;
    private String pluginName;
    private String pluginDescription;
    private byte[] bytecode;
    private String pluginType;
    private String runtime;
    private String kind;
    private String language;
    private String entrypoint;
    private String parameterSchema;
    private String digest;
    private String riskLevel;
    private Map<String, Object> requirements;
    private String paramsDemo;
    private String version;
    private String createUserId;
    private String createTime;
    private String updateTime;
    private String remark;

    public Plugin() {
        this.version = "1.0";
    }

    public String getPluginId() {
        return pluginId;
    }

    public void setPluginId(String pluginId) {
        this.pluginId = pluginId;
    }

    public String getPluginName() {
        return pluginName;
    }

    public void setPluginName(String pluginName) {
        this.pluginName = pluginName;
    }

    public String getPluginDescription() {
        return pluginDescription;
    }

    public void setPluginDescription(String pluginDescription) {
        this.pluginDescription = pluginDescription;
    }

    public byte[] getBytecode() {
        return bytecode;
    }

    public void setBytecode(byte[] bytecode) {
        this.bytecode = bytecode;
    }

    public String getPluginType() {
        return pluginType;
    }

    public void setPluginType(String pluginType) {
        this.pluginType = pluginType;
    }

    public String getRuntime() { return runtime; }

    public void setRuntime(String runtime) { this.runtime = runtime; }

    public String getKind() { return kind; }

    public void setKind(String kind) { this.kind = kind; }

    public String getLanguage() { return language; }

    public void setLanguage(String language) { this.language = language; }

    public String getEntrypoint() { return entrypoint; }

    public void setEntrypoint(String entrypoint) { this.entrypoint = entrypoint; }

    public String getParameterSchema() { return parameterSchema; }

    public void setParameterSchema(String parameterSchema) { this.parameterSchema = parameterSchema; }

    public String getDigest() { return digest; }

    public void setDigest(String digest) { this.digest = digest; }

    public String getRiskLevel() { return riskLevel; }

    public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }

    public Map<String, Object> getRequirements() {
        return requirements == null ? new LinkedHashMap<>() : requirements;
    }

    public void setRequirements(Map<String, Object> requirements) { this.requirements = requirements; }

    public String resolveRuntime() {
        if (runtime == null || runtime.isBlank()) {
            throw new IllegalStateException("插件 runtime 未配置");
        }
        return runtime.trim().toLowerCase();
    }

    public String getParamsDemo() {
        return paramsDemo;
    }

    public void setParamsDemo(String paramsDemo) {
        this.paramsDemo = paramsDemo;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getCreateUserId() {
        return createUserId;
    }

    public void setCreateUserId(String createUserId) {
        this.createUserId = createUserId;
    }

    public String getCreateTime() {
        return createTime;
    }

    public void setCreateTime(String createTime) {
        this.createTime = createTime;
    }

    public String getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(String updateTime) {
        this.updateTime = updateTime;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    @Override
    public String toString() {
        return JsonUtil.toJsonString(this);
    }
}
