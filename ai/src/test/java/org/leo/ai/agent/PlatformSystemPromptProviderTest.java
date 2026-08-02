package org.leo.ai.agent;

import org.junit.jupiter.api.Test;
import org.leo.ai.service.LeoSkillsProvider;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlatformSystemPromptProviderTest {

    @Test
    void requiresIndependentShellConfigurationAndExplicitUserChoices() {
        LeoSkillsProvider skillsProvider = mock(LeoSkillsProvider.class);
        when(skillsProvider.getFormattedSkills("platform")).thenReturn("");

        String prompt = new PlatformSystemPromptProvider(skillsProvider).getSystemMessage("memory");

        assertTrue(prompt.contains("Shell 生成是独立的制品生成任务"));
        assertTrue(prompt.contains("禁止查询 Puppet"));
        assertTrue(prompt.contains("getShellGeneratorMeta 和 getDisguises"));
        assertTrue(prompt.contains("是否混淆"));
        assertTrue(prompt.contains(
                "createJavaCoreArtifact → designWebShellWrapper → assembleWebShellWrapper"));
        assertTrue(prompt.contains("Core 字节码只保存在服务端"));
    }
}
