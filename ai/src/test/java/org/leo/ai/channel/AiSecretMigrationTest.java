package org.leo.ai.channel;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.AiModelConfig;
import org.leo.core.entity.AiProvider;
import org.leo.dao.mapper.AiModelCapabilityMapper;
import org.leo.dao.mapper.AiModelConfigMapper;
import org.leo.dao.mapper.AiProviderMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiSecretMigrationTest {

    @Test
    void migratesProviderAndModelSecretsToCiphertext() {
        AiModelConfigMapper modelMapper = mock(AiModelConfigMapper.class);
        AiProviderMapper providerMapper = mock(AiProviderMapper.class);
        AiModelCapabilityMapper capabilityMapper = mock(AiModelCapabilityMapper.class);
        AiSecretCryptoService crypto = new AiSecretCryptoService("migration-key", "unused");
        AiModelConfigService service = new AiModelConfigService(
                modelMapper, providerMapper, capabilityMapper, crypto);

        AiProvider provider = new AiProvider();
        provider.setApiKey("provider-key");
        provider.setHeadersJson("{\"Authorization\":\"secret\"}");
        AiModelConfig model = new AiModelConfig();
        model.setApiKey("model-key");
        model.setHeadersJson("{\"X-Secret\":\"value\"}");
        when(providerMapper.listAll()).thenReturn(List.of(provider));
        when(modelMapper.listAll()).thenReturn(List.of(model));

        assertEquals(2, service.migrateSecretsAtRest());
        assertTrue(crypto.isEncrypted(provider.getApiKey()));
        assertTrue(crypto.isEncrypted(provider.getHeadersJson()));
        assertTrue(crypto.isEncrypted(model.getApiKey()));
        assertTrue(crypto.isEncrypted(model.getHeadersJson()));
        verify(providerMapper).update(provider);
        verify(modelMapper).update(model);
    }
}
