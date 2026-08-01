package org.leo.ai.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leo.core.config.LeoConfig;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeoSkillsProviderTest {

    @TempDir
    Path tempDir;

    private String previousVfsPath;
    private SkillRegistryService registry;
    private LeoSkillsProvider provider;

    @BeforeEach
    void setUp() {
        previousVfsPath = LeoConfig.getVfsPath();
        ReflectionTestUtils.setField(LeoConfig.class, "VFS_PATH", tempDir.toString());
        registry = new SkillRegistryService();
        provider = new LeoSkillsProvider(registry);
    }

    @AfterEach
    void tearDown() {
        ReflectionTestUtils.setField(LeoConfig.class, "VFS_PATH", previousVfsPath);
    }

    @Test
    void indexesOnlyRuntimeEligibleMetadataAndDoesNotPreloadBodies() throws Exception {
        writeSkill("good-skill", true, "usable & targeted", "SECRET_BODY_MARKER");
        writeSkill("disabled-skill", false, "disabled", "disabled body");

        Path invalidDir = tempDir.resolve("skills/puppet-node/broken-skill");
        Files.createDirectories(invalidDir);
        Files.writeString(invalidDir.resolve("SKILL.md"), "not valid frontmatter");

        String index = provider.getFormattedSkills("puppet-node");

        assertTrue(index.contains("<name>good-skill</name>"));
        assertTrue(index.contains("usable &amp; targeted"));
        assertFalse(index.contains("SECRET_BODY_MARKER"));
        assertFalse(index.contains("disabled-skill"));
        assertFalse(index.contains("broken-skill"));
    }

    @Test
    void invalidationMakesNewCatalogEntriesVisible() throws Exception {
        assertTrue(provider.getFormattedSkills("platform").isEmpty());
        writeSkill("new-skill", true, "new skill", "body", "platform");

        registry.invalidate();
        provider.invalidate();

        assertTrue(provider.getFormattedSkills("platform").contains("<name>new-skill</name>"));
    }

    private void writeSkill(String name, boolean enabled, String description, String body) throws Exception {
        writeSkill(name, enabled, description, body, "puppet-node");
    }

    private void writeSkill(String name, boolean enabled, String description,
                            String body, String scope) throws Exception {
        Path skillDir = tempDir.resolve("skills").resolve(scope).resolve(name);
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: %s
                description: "%s"
                ---

                %s
                """.formatted(name, description, body));
        Files.writeString(skillDir.resolve("manifest.yaml"), """
                schemaVersion: 1
                id: leo.test.%s
                name: %s
                version: 1.0.0
                scope: %s
                domain: operation
                category: discovery
                mode: assess
                platforms: [linux]
                targets: [host]
                risk: low
                accessMode: read-only
                status: published
                source: custom
                owner: test
                enabled: %s
                """.formatted(name, name, scope, enabled));
    }
}
