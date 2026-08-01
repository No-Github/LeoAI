package org.leo.ai.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leo.core.config.LeoConfig;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltinSkillCatalogTest {

    @TempDir
    Path tempDir;

    @Test
    void allBuiltinSkillsPassCatalogValidation() throws Exception {
        String previous = LeoConfig.getVfsPath();
        ReflectionTestUtils.setField(LeoConfig.class, "VFS_PATH", tempDir.toString());
        try {
            copyBuiltin("platform", List.of(
                    "develop-disguise", "develop-fingerprint", "exploit-suggest", "shell-obfuscation"));
            copyBuiltin("puppet-node", List.of(
                    "recon-basic-info", "hunt-credentials", "escalate-linux-privilege",
                    "lateral-move-ssh", "persistence-linux"));

            SkillRegistryService registry = new SkillRegistryService();
            List<SkillInspection> platform = registry.health("platform");
            List<SkillInspection> puppet = registry.health("puppet-node");

            assertEquals(4, platform.size());
            assertEquals(5, puppet.size());
            assertTrue(platform.stream().allMatch(SkillInspection::valid),
                    () -> "invalid platform catalog: " + platform);
            assertTrue(puppet.stream().allMatch(SkillInspection::valid),
                    () -> "invalid puppet catalog: " + puppet);
            assertEquals(3, registry.listSkills("platform").size(),
                    "high-risk shell generator must be disabled by default");
            assertEquals(3, registry.listSkills("puppet-node").size(),
                    "high-risk lateral movement and persistence must be disabled by default");
        } finally {
            ReflectionTestUtils.setField(LeoConfig.class, "VFS_PATH", previous);
        }
    }

    private void copyBuiltin(String scope, List<String> names) throws Exception {
        for (String name : names) {
            Path target = tempDir.resolve("skills").resolve(scope).resolve(name);
            Files.createDirectories(target);
            copyResource("skills/" + scope + "/" + name + "/SKILL.md", target.resolve("SKILL.md"));
            copyResource("skills/" + scope + "/" + name + "/manifest.yaml", target.resolve("manifest.yaml"));
        }
    }

    private void copyResource(String resource, Path target) throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertTrue(input != null, () -> "missing resource: " + resource);
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
