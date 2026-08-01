package org.leo.web.util;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.AiExecutionPolicy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControllerUtilPolicyPromptTest {

    @Test
    void describesServerAuthorizationWithoutLegacyConfirmationPromise() {
        String prompt = ControllerUtil.buildAiPolicyPrompt(
                new AiExecutionPolicy("user-1", "alice", "normal"),
                "执行任务");

        assertTrue(prompt.contains("当前工具列表中已授权的能力"));
        assertTrue(prompt.contains("权限不足，立即停止重试"));
        assertFalse(prompt.contains("自动向用户请求确认"));
        assertFalse(prompt.contains("user_rejected"));
    }
}
