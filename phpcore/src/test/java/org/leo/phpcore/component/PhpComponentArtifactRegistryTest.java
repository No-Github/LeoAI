package org.leo.phpcore.component;

import org.junit.jupiter.api.Test;
import org.leo.core.component.runtime.ComponentArtifact;
import org.leo.core.component.runtime.ComponentDeliveryMode;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhpComponentArtifactRegistryTest {

    @Test
    void loadsIndependentDigestAddressedArtifacts() {
        PhpComponentArtifactRegistry registry = new PhpComponentArtifactRegistry();
        assertEquals(10, registry.getComponentIds().size());
        ComponentArtifact artifact = registry.getRequired("FileComponent");
        assertEquals(ComponentDeliveryMode.DISK_CACHE, artifact.getDeliveryMode());
        assertEquals(64, artifact.getDigest().length());
        String source = new String(artifact.getContent(), StandardCharsets.UTF_8);
        assertTrue(source.startsWith("<?php"));
        assertTrue(source.contains("'id' => 'FileComponent'"));
        assertThrows(IllegalArgumentException.class, () -> registry.getRequired("MissingComponent"));
    }

    @Test
    void componentSourcesKeepPhp56SyntaxBaseline() {
        PhpComponentArtifactRegistry registry = new PhpComponentArtifactRegistry();
        for (String componentId : registry.getComponentIds()) {
            String source = new String(registry.getRequired(componentId).getContent(), StandardCharsets.UTF_8);
            assertFalse(source.matches("(?s).*function\\s*\\([^)]*\\b(?:string|int|bool|float)\\s+\\$.*"),
                    componentId + " contains PHP 7 parameter types");
            assertFalse(source.matches("(?s).*function\\s*\\([^)]*\\)\\s*:\\s*(?:void|bool|string|array).*"),
                    componentId + " contains PHP 7 return types");
            assertFalse(source.contains("PHP_OS_FAMILY,"), componentId + " uses an unsafe PHP_OS_FAMILY fallback");
            assertFalse(source.contains("leo_array_get"), componentId + " depends on the bootstrap getter");
            assertFalse(source.contains("leo_function_available"), componentId + " depends on bootstrap capability helpers");
            assertFalse(source.contains("leo_os_family"), componentId + " depends on bootstrap OS helpers");
        }
    }
}
