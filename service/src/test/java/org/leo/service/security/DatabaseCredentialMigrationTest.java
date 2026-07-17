package org.leo.service.security;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.PuppetDatabaseConnection;
import org.leo.dao.mapper.PuppetDatabaseConnectionMapper;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseCredentialMigrationTest {

    @Test
    void migratesHistoricalPlaintextPasswords() {
        PuppetDatabaseConnectionMapper mapper = mock(PuppetDatabaseConnectionMapper.class);
        DatabaseCredentialCryptoService crypto = new DatabaseCredentialCryptoService("migration-key", "unused");
        PuppetDatabaseConnection historical = new PuppetDatabaseConnection();
        historical.setConnectionId("connection-1");
        historical.setPassword("historical-secret");
        when(mapper.selectAll()).thenReturn(List.of(historical));

        new DatabaseCredentialMigration(mapper, crypto).run();

        verify(mapper).updatePassword(eq("connection-1"),
                org.mockito.ArgumentMatchers.argThat(value ->
                        crypto.isEncrypted(value) && "historical-secret".equals(crypto.decrypt(value))));
    }
}
