package org.leo.ai.agent;

import org.leo.ai.tools.platform.SkillActivationTools;
import org.leo.ai.tools.common.UserInputTools;
import org.leo.ai.tools.common.AgentWorkspaceCommandTools;
import org.leo.ai.tools.common.AgentWorkspaceTools;
import org.leo.ai.tools.common.WebResearchTools;
import org.leo.ai.tools.puppetnode.BasicInfoTools;
import org.leo.ai.tools.puppetnode.CommandTools;
import org.leo.ai.tools.puppetnode.CredentialHarvestTools;
import org.leo.ai.tools.puppetnode.DatabaseConnectionTools;
import org.leo.ai.tools.puppetnode.FileTools;
import org.leo.ai.tools.puppetnode.HttpRequestTools;
import org.leo.ai.tools.puppetnode.JavaPluginTools;
import org.leo.ai.tools.common.PlanTools;
import org.leo.ai.tools.puppetnode.ResourceTools;
import org.leo.ai.tools.puppetnode.ReverseTunnelTools;
import org.leo.ai.tools.puppetnode.ScanTools;
import org.leo.ai.tools.puppetnode.ScriptTools;
import org.leo.ai.tools.puppetnode.SqlTools;
import org.leo.ai.tools.puppetnode.WebRuntimeTools;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

/** Puppet Node Agent 的完整、不可变工具配置。 */
@Component
public class PuppetNodeToolBundle {

    private final List<Object> tools;

    public PuppetNodeToolBundle(
            CommandTools commandTools,
            BasicInfoTools basicInfoTools,
            ReverseTunnelTools reverseTunnelTools,
            FileTools fileTools,
            ScanTools scanTools,
            CredentialHarvestTools credentialHarvestTools,
            DatabaseConnectionTools databaseConnectionTools,
            WebRuntimeTools webRuntimeTools,
            JavaPluginTools javaPluginTools,
            HttpRequestTools httpRequestTools,
            ScriptTools scriptTools,
            SqlTools sqlTools,
            ResourceTools resourceTools,
            AgentWorkspaceTools agentWorkspaceTools,
            AgentWorkspaceCommandTools agentWorkspaceCommandTools,
            WebResearchTools webResearchTools,
            PlanTools planTools,
            UserInputTools userInputTools,
            @Qualifier("puppetNodeSkillActivationTools") SkillActivationTools skillActivationTools) {
        this.tools = List.of(
                commandTools, basicInfoTools, reverseTunnelTools,
                fileTools, scanTools,
                credentialHarvestTools, databaseConnectionTools,
                webRuntimeTools, javaPluginTools,
                httpRequestTools, scriptTools, sqlTools, resourceTools,
                agentWorkspaceTools, agentWorkspaceCommandTools,
                webResearchTools, planTools, userInputTools,
                skillActivationTools);
    }

    public List<Object> tools() {
        return tools;
    }
}
