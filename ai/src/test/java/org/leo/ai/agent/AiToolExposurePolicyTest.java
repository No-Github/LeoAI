package org.leo.ai.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leo.ai.platform.PlatformAiState;
import org.leo.ai.platform.PlatformAiStateStore;
import org.leo.ai.service.SkillMeta;
import org.leo.ai.service.SkillRegistryService;
import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.core.puppet.capability.BasicInfoCapable;
import org.leo.core.runtime.CapabilitySet;
import org.leo.core.session.PuppetNodeSession;
import org.leo.core.session.PuppetNodeSessionContainer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class AiToolExposurePolicyTest {

    private static final String PLATFORM_STATE_ID = "tool-exposure-platform";

    @AfterEach
    void cleanUp() {
        PlatformAiStateStore.remove(PLATFORM_STATE_ID);
        PuppetNodeSessionContainer.clearAllSessions();
    }

    @Test
    void exposesSkillToolsOnlyAfterActivationIncludingDependencies() {
        SkillRegistryService registry = mock(SkillRegistryService.class);
        SkillMeta parent = skill("parent-skill", List.of("parentAction"),
                List.of("dependency-skill"));
        SkillMeta dependency = skill("dependency-skill", List.of("dependencyAction"),
                List.of());
        when(registry.listSkills(SkillRegistryService.SCOPE_PLATFORM))
                .thenReturn(List.of(parent, dependency));
        AiToolExposurePolicy policy = new AiToolExposurePolicy(
                new AgentRuntimeResolver(), registry);
        PlatformAiState state = PlatformAiStateStore.create(PLATFORM_STATE_ID);

        assertFalse(policy.isVisible(AiToolAuthorizationPolicy.AgentScope.PLATFORM,
                PLATFORM_STATE_ID, "parentAction"));
        assertFalse(policy.isVisible(AiToolAuthorizationPolicy.AgentScope.PLATFORM,
                PLATFORM_STATE_ID, "dependencyAction"));

        state.activateSkill("parent-skill");

        assertTrue(policy.isVisible(AiToolAuthorizationPolicy.AgentScope.PLATFORM,
                PLATFORM_STATE_ID, "parentAction"));
        assertTrue(policy.isVisible(AiToolAuthorizationPolicy.AgentScope.PLATFORM,
                PLATFORM_STATE_ID, "dependencyAction"));
    }

    @Test
    void keepsCoreToolsVisibleEvenWhenAManifestMentionsThem() {
        SkillRegistryService registry = mock(SkillRegistryService.class);
        SkillMeta workspaceSkill = skill("workspace-skill",
                List.of("workspaceReadText"), List.of());
        when(registry.listSkills(SkillRegistryService.SCOPE_PLATFORM))
                .thenReturn(List.of(workspaceSkill));
        AiToolExposurePolicy policy = new AiToolExposurePolicy(
                new AgentRuntimeResolver(), registry);

        assertTrue(policy.isVisible(AiToolAuthorizationPolicy.AgentScope.PLATFORM,
                PLATFORM_STATE_ID, "workspaceReadText"));
    }

    @Test
    void filtersPuppetToolsByRuntimeCapability() {
        SkillRegistryService registry = mock(SkillRegistryService.class);
        when(registry.listSkills(SkillRegistryService.SCOPE_PUPPET_NODE))
                .thenReturn(List.of());
        AbstractPuppetNode node = mock(AbstractPuppetNode.class,
                withSettings().extraInterfaces(BasicInfoCapable.class));
        when(node.getCapabilitySet()).thenReturn(CapabilitySet.empty());
        PuppetNodeSession session = new PuppetNodeSession();
        session.setSessionId("capability-session");
        session.setPuppetNode(node);
        PuppetNodeSessionContainer.addSession("capability-session", session);
        AiToolExposurePolicy policy = new AiToolExposurePolicy(
                new AgentRuntimeResolver(), registry);

        assertTrue(policy.isVisible(AiToolAuthorizationPolicy.AgentScope.PUPPET_NODE,
                "capability-session", "getBasicInfo"));
        assertFalse(policy.isVisible(AiToolAuthorizationPolicy.AgentScope.PUPPET_NODE,
                "capability-session", "getClassBytecode"));
    }

    private static SkillMeta skill(String name, List<String> requiredTools,
                                   List<String> requiredSkills) {
        SkillMeta skill = mock(SkillMeta.class);
        when(skill.getName()).thenReturn(name);
        when(skill.getRequiredTools()).thenReturn(requiredTools);
        when(skill.getRequiredSkills()).thenReturn(requiredSkills);
        return skill;
    }
}
