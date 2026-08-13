package org.leo.ai.agent;

import org.leo.ai.tools.platform.DisguiseTools;
import org.leo.ai.tools.platform.FingerprintTools;
import org.leo.ai.tools.platform.PluginTools;
import org.leo.ai.tools.platform.PuppetTools;
import org.leo.ai.tools.platform.ShellGeneratorTools;
import org.leo.ai.tools.platform.SkillActivationTools;
import org.leo.ai.tools.platform.TeamTools;
import org.leo.ai.tools.platform.UserTools;
import org.leo.ai.tools.common.UserInputTools;
import org.leo.ai.tools.common.OperationAssessmentTools;
import org.leo.ai.tools.common.PlanTools;
import org.leo.ai.tools.common.AgentWorkspaceCommandTools;
import org.leo.ai.tools.common.AgentWorkspaceTools;
import org.leo.ai.tools.common.WebResearchTools;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Platform Agent 的完整、不可变工具配置。 */
@Component
public class PlatformToolBundle {

    private final List<Object> tools;

    public PlatformToolBundle(
            PuppetTools puppetTools,
            UserTools userTools,
            TeamTools teamTools,
            PluginTools pluginTools,
            FingerprintTools fingerprintTools,
            DisguiseTools disguiseTools,
            ShellGeneratorTools shellGeneratorTools,
            AgentWorkspaceTools agentWorkspaceTools,
            AgentWorkspaceCommandTools agentWorkspaceCommandTools,
            WebResearchTools webResearchTools,
            PlanTools planTools,
            UserInputTools userInputTools,
            OperationAssessmentTools operationAssessmentTools,
            @Qualifier("platformSkillActivationTools") SkillActivationTools skillActivationTools) {
        this.tools = List.of(
                puppetTools, userTools, teamTools,
                pluginTools, fingerprintTools, disguiseTools,
                shellGeneratorTools, agentWorkspaceTools, agentWorkspaceCommandTools,
                webResearchTools, planTools, userInputTools, operationAssessmentTools,
                skillActivationTools);
    }

    public List<Object> tools() {
        return tools;
    }

    public List<Object> toolsWith(Object additionalTools) {
        if (additionalTools == null) return tools;
        List<Object> combined = new ArrayList<>(tools.size() + 1);
        combined.addAll(tools);
        combined.add(additionalTools);
        return List.copyOf(combined);
    }
}
