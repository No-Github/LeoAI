package org.leo.ai.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.langchain4j.model.output.structured.Description;

import java.util.List;

/** createPlan 的稳定输入模型，确保工具 Schema 公开每个步骤字段。 */
public class AiPlanStepInput {

    @JsonProperty(required = true)
    @Description("清晰描述本步骤要完成的业务结果")
    private String description;
    @JsonProperty(required = false)
    @Description("建议使用的工具名")
    private String toolHint;
    @JsonProperty(required = false)
    @Description("是否可与其他独立步骤并行；默认 false")
    private Boolean parallel;
    @JsonProperty(required = false)
    @Description("步骤完成的可验证标准")
    private String successCriteria;
    @JsonProperty(required = false)
    @Description("失败后的最大重试次数；默认 1")
    private Integer maxRetries;
    @JsonProperty(required = false)
    @Description("本步骤依赖的更早步骤序号列表")
    private List<Integer> dependsOn;

    public AiPlanStepInput() {
    }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getToolHint() { return toolHint; }
    public void setToolHint(String toolHint) { this.toolHint = toolHint; }
    public Boolean getParallel() { return parallel; }
    public void setParallel(Boolean parallel) { this.parallel = parallel; }
    public String getSuccessCriteria() { return successCriteria; }
    public void setSuccessCriteria(String successCriteria) { this.successCriteria = successCriteria; }
    public Integer getMaxRetries() { return maxRetries; }
    public void setMaxRetries(Integer maxRetries) { this.maxRetries = maxRetries; }
    public List<Integer> getDependsOn() { return dependsOn; }
    public void setDependsOn(List<Integer> dependsOn) { this.dependsOn = dependsOn; }
}
