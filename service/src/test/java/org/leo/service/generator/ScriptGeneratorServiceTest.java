package org.leo.service.generator;

import org.junit.jupiter.api.Test;
import org.leo.core.generator.GeneratedArtifact;
import org.leo.core.generator.GenerationRequest;
import org.leo.core.generator.ScriptGeneratorProvider;
import org.leo.core.runtime.PuppetRuntime;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScriptGeneratorServiceTest {

    @Test
    void indexesMetadataAndDispatchesByRuntime() throws Exception {
        ScriptGeneratorProvider provider = provider(PuppetRuntime.PHP);
        ScriptGeneratorService service = new ScriptGeneratorService(List.of(provider));

        assertEquals("php", ((Map<?, ?>) service.getMetadata().get("php")).get("runtime"));
        GeneratedArtifact artifact = service.generate(new GenerationRequest(
                PuppetRuntime.PHP, "webshell", null, null, Map.of()));
        assertEquals("php", artifact.getFileExtension());
    }

    @Test
    void rejectsDuplicateProviders() {
        assertThrows(IllegalStateException.class, () -> new ScriptGeneratorService(
                List.of(provider(PuppetRuntime.PHP), provider(PuppetRuntime.PHP))));
    }

    private ScriptGeneratorProvider provider(PuppetRuntime runtime) {
        return new ScriptGeneratorProvider() {
            public PuppetRuntime getRuntime() { return runtime; }
            public Map<String, Object> getMetadata() { return Map.of("runtime", runtime.getValue()); }
            public GeneratedArtifact generate(GenerationRequest request) {
                return new GeneratedArtifact("<?php echo 'ok';", "php", "application/x-httpd-php",
                        Map.of(), List.of());
            }
        };
    }
}
