package org.leo.service;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.PuppetJdbc;
import org.leo.dao.mapper.PuppetJdbcMapper;
import org.leo.service.security.JdbcCredentialCryptoService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PuppetJdbcServiceTest {

    @Test
    void encryptsPasswordAtMapperBoundaryAndKeepsCallerValuePlaintext() throws Exception {
        PuppetJdbcMapper mapper = mock(PuppetJdbcMapper.class);
        JdbcCredentialCryptoService crypto = new JdbcCredentialCryptoService("service-key", "unused");
        PuppetJdbcService service = new PuppetJdbcService(mapper, crypto);
        PuppetJdbc connection = connection("plain-secret");
        doAnswer(invocation -> {
            PuppetJdbc persisted = invocation.getArgument(0);
            assertTrue(crypto.isEncrypted(persisted.getPassword()));
            assertEquals("plain-secret", crypto.decrypt(persisted.getPassword()));
            return 1;
        }).when(mapper).insert(any(PuppetJdbc.class));

        assertTrue(service.saveOrUpdate(connection));
        assertEquals("plain-secret", connection.getPassword());
    }

    @Test
    void decryptsStoredPasswordForInternalUse() {
        PuppetJdbcMapper mapper = mock(PuppetJdbcMapper.class);
        JdbcCredentialCryptoService crypto = new JdbcCredentialCryptoService("service-key", "unused");
        PuppetJdbc stored = connection(crypto.encrypt("plain-secret"));
        stored.setConnId("conn-1");
        when(mapper.selectById("conn-1")).thenReturn(stored);

        PuppetJdbc result = new PuppetJdbcService(mapper, crypto).findById("conn-1");

        assertEquals("plain-secret", result.getPassword());
    }

    private static PuppetJdbc connection(String password) {
        PuppetJdbc connection = new PuppetJdbc("test", "puppet-1", "mysql", "localhost", 3306, "db");
        connection.setPassword(password);
        connection.setCreateUserId("user-1");
        return connection;
    }
}
