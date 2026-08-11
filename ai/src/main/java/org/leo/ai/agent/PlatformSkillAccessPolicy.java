package org.leo.ai.agent;

import org.leo.core.ai.AiRuntimeState;
import org.leo.core.entity.AiExecutionPolicy;

import java.util.Collection;
import java.util.Set;

/** 平台 Skill 索引与激活共用的最小权限过滤规则。 */
public final class PlatformSkillAccessPolicy {

    private static final Set<String> ADMIN_ONLY_TOOLS = Set.of(
            "listUsers", "getUser", "addUser", "updateUser", "deleteUser",
            "listTeams", "getTeam", "addTeam", "updateTeam", "deleteTeam",
            "getDisguises", "getDisguiseById", "testDisguise", "addDisguise",
            "updateDisguise", "deleteDisguise",
            "listPlugins", "getPluginById", "addPlugin", "updatePlugin", "deletePlugin",
            "decompilePluginBytecode",
            "listFingerprints", "getFingerprintById", "saveFingerprint", "deleteFingerprint"
    );

    private PlatformSkillAccessPolicy() {
    }

    public static boolean mayUse(AiRuntimeState runtime, Collection<String> requiredTools) {
        if (requiredTools == null || requiredTools.stream().noneMatch(ADMIN_ONLY_TOOLS::contains)) {
            return true;
        }
        AiExecutionPolicy policy = runtime != null ? runtime.getExecutionPolicy() : null;
        return policy != null && policy.isAdmin();
    }
}
