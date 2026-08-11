package org.leo.ai.agent;

import dev.langchain4j.agent.tool.Tool;
import org.junit.jupiter.api.Test;
import org.leo.ai.platform.PlatformAiState;
import org.leo.ai.tools.platform.DisguiseTools;
import org.leo.ai.tools.platform.FingerprintTools;
import org.leo.ai.tools.platform.PluginTools;
import org.leo.ai.tools.platform.TeamTools;
import org.leo.ai.tools.platform.UserTools;
import org.leo.core.entity.AiExecutionPolicy;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformSkillAccessPolicyTest {

    @Test
    void hidesSkillsThatDependOnAdminToolsFromOrdinaryRuntime() {
        PlatformAiState runtime = new PlatformAiState("normal");
        runtime.setExecutionPolicy(new AiExecutionPolicy("u1", "alice", "normal"));

        assertFalse(PlatformSkillAccessPolicy.mayUse(
                runtime, List.of("listFingerprints", "getFingerprintById")));
        assertTrue(PlatformSkillAccessPolicy.mayUse(
                runtime, List.of("getShellGeneratorMeta")));
    }

    @Test
    void permitsAdminRuntime() {
        PlatformAiState runtime = new PlatformAiState("admin");
        runtime.setExecutionPolicy(new AiExecutionPolicy("u2", "root", "admin"));

        assertTrue(PlatformSkillAccessPolicy.mayUse(runtime, List.of("listUsers")));
    }

    @Test
    void permissionFilterTracksEveryAdminToolDeclaration() {
        PlatformAiState runtime = new PlatformAiState("normal-contract");
        runtime.setExecutionPolicy(new AiExecutionPolicy("u1", "alice", "normal"));
        List<Class<?>> adminToolClasses = List.of(
                DisguiseTools.class, FingerprintTools.class, PluginTools.class,
                TeamTools.class, UserTools.class);

        for (Class<?> type : adminToolClasses) {
            for (var method : type.getDeclaredMethods()) {
                Tool tool = method.getAnnotation(Tool.class);
                if (tool == null) continue;
                String name = tool.name().isBlank() ? method.getName() : tool.name();
                assertFalse(PlatformSkillAccessPolicy.mayUse(runtime, List.of(name)),
                        "管理员工具未进入 Skill 权限过滤表: " + name);
            }
        }
    }
}
