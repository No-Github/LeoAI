package org.leo.ai.channel;

import org.junit.jupiter.api.Test;
import org.leo.ai.service.AiErrorClassifier;
import org.leo.core.entity.AiModelConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiModelFailoverServiceTest {

    private final AiErrorClassifier classifier = new AiErrorClassifier();

    @Test
    void switchesOnlyNewSelectionsAfterTransientFailureCircuitOpens() {
        AiModelConfigService configService = mock(AiModelConfigService.class);
        AiModelFailoverService service = new AiModelFailoverService(configService);
        AiModelConfig primary = model(1, "主模型", 2);
        AiModelConfig fallback = model(2, "备用模型", null);
        when(configService.resolve(2)).thenReturn(fallback);

        service.recordFailure(1, classifier.classify("request timed out"));
        assertFalse(service.snapshot(1).circuitOpen());
        assertEquals(1, service.selectForExecution(primary).effectiveConfig().getId());

        service.recordFailure(1, classifier.classify("request timed out"));
        AiModelFailoverService.ModelSelection selection = service.selectForExecution(primary);

        assertTrue(selection.failover());
        assertEquals(2, selection.effectiveConfig().getId());
        assertTrue(service.snapshot(1).circuitOpen());

        service.recordSuccess(1);
        assertFalse(service.snapshot(1).circuitOpen());
        assertEquals(1, service.selectForExecution(primary).effectiveConfig().getId());
    }

    @Test
    void doesNotOpenCircuitForConfigurationFailures() {
        AiModelFailoverService service = new AiModelFailoverService(mock(AiModelConfigService.class));

        service.recordFailure(9, classifier.classify("HTTP 401 Unauthorized"));
        service.recordFailure(9, classifier.classify("HTTP 401 Unauthorized"));

        AiModelFailoverService.HealthSnapshot snapshot = service.snapshot(9);
        assertFalse(snapshot.circuitOpen());
        assertEquals("auth", snapshot.lastCategory());
    }

    private static AiModelConfig model(int id, String name, Integer fallbackId) {
        AiModelConfig config = new AiModelConfig();
        config.setId(id);
        config.setName(name);
        config.setEnabled(1);
        config.setFallbackModelId(fallbackId);
        return config;
    }
}
