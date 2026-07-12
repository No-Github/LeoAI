package org.leo.service.security;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.PuppetJdbc;
import org.leo.dao.mapper.PuppetJdbcMapper;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcCredentialMigrationTest {

    @Test
    void migratesLegacyPlaintextPasswords() {
        PuppetJdbcMapper mapper = mock(PuppetJdbcMapper.class);
        JdbcCredentialCryptoService crypto = new JdbcCredentialCryptoService("migration-key", "unused");
        PuppetJdbc legacy = new PuppetJdbc();
        legacy.setConnId("conn-1");
        legacy.setPassword("legacy-secret");
        when(mapper.selectAll()).thenReturn(List.of(legacy));

        new JdbcCredentialMigration(mapper, crypto).run();

        verify(mapper).updatePassword(eq("conn-1"),
                org.mockito.ArgumentMatchers.argThat(value ->
                        crypto.isEncrypted(value) && "legacy-secret".equals(crypto.decrypt(value))));
    }
}
