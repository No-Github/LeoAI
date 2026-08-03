package org.leo.service;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.PuppetDatabaseConnection;
import org.leo.core.puppet.database.DatabaseConnectionSpec;
import org.leo.dao.mapper.PuppetDatabaseConnectionMapper;
import org.leo.service.security.DatabaseCredentialCryptoService;
import org.leo.service.sql.dialect.SqlDialectRegistry;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseConnectionProfileServiceTest {

    @Test
    void createsSanitizedPuppetOwnedProfile() {
        Fixture fixture = fixture();
        doAnswer(invocation -> {
            PuppetDatabaseConnection saved = invocation.getArgument(0);
            assertEquals("puppet-1", saved.getPuppetId());
            assertEquals("user-1", saved.getCreateUserId());
            return 1;
        }).when(fixture.mapper()).insert(any(PuppetDatabaseConnection.class));

        Map<String, Object> result = fixture.profile().create(
                "user-1", "puppet-1", Map.of(
                        "connectionName", "inventory",
                        "connection", mysqlConnection("db.internal", "secret")));

        assertEquals("inventory", result.get("connectionName"));
        assertFalse(String.valueOf(result).contains("secret"));
    }

    @Test
    void patchUpdateRetainsUnspecifiedConnectionFieldsAndStoredPassword() {
        Fixture fixture = fixture();
        PuppetDatabaseConnection existing = existing(fixture.persistence(), fixture.crypto());
        when(fixture.mapper().selectById("connection-1")).thenReturn(existing);
        doAnswer(invocation -> {
            PuppetDatabaseConnection persisted = invocation.getArgument(0);
            assertEquals("existing-secret", fixture.crypto().decrypt(persisted.getPassword()));
            return 1;
        }).when(fixture.mapper()).update(any(PuppetDatabaseConnection.class));

        fixture.profile().update("user-2", "puppet-1", "connection-1", Map.of(
                "description", "collected from application.yml",
                "connection", Map.of("host", "db-new.internal")));

        DatabaseConnectionSpec restored = fixture.persistence().toConnectionSpec(existing);
        assertEquals("db-new.internal", restored.getHost());
        assertEquals("inventory", restored.getDatabase());
        assertEquals("existing-secret", restored.getPassword());
        assertEquals("collected from application.yml", existing.getDescription());
    }

    @Test
    void rejectsUnknownStandardDialectAndExplainsGenericCustomFallback() {
        Fixture fixture = fixture();

        DatabaseConnectionProfileException error = assertThrows(
                DatabaseConnectionProfileException.class,
                () -> fixture.profile().create("user-1", "puppet-1", Map.of(
                        "connection", Map.of(
                                "dialect", "inventeddb",
                                "connectionMode", "standard",
                                "host", "db.internal"))));

        assertEquals(DatabaseConnectionProfileException.Kind.VALIDATION, error.getKind());
        assertTrue(error.getMessage().contains("dialect=generic"));
        assertTrue(error.getMessage().contains("driverClass"));
        assertTrue(error.getMessage().contains("jdbcUrl"));
    }

    @Test
    void acceptsDomesticStandardDialectAliasesAndPersistsCanonicalIds() {
        Fixture fixture = fixture();
        when(fixture.mapper().insert(any(PuppetDatabaseConnection.class))).thenReturn(1);

        Map<String, Object> dm = fixture.profile().create(
                "user-1", "puppet-1", Map.of(
                        "connection", Map.of(
                                "dialect", "dameng",
                                "connectionMode", "standard",
                                "host", "dm.internal")));

        assertEquals("dm", ((Map<?, ?>) dm.get("connection")).get("dialect"));
    }

    @Test
    void acceptsGenericCustomJdbcProfile() {
        Fixture fixture = fixture();
        when(fixture.mapper().insert(any(PuppetDatabaseConnection.class))).thenReturn(1);

        Map<String, Object> result = fixture.profile().create(
                "user-1", "puppet-1", Map.of(
                        "connection", Map.of(
                                "dialect", "generic",
                                "connectionMode", "custom",
                                "runtimeOptions", Map.of("java", Map.of(
                                        "driverClass", "dm.jdbc.driver.DmDriver",
                                        "jdbcUrl", "jdbc:dm://db.internal:5236/APP")))));

        assertEquals("generic", ((Map<?, ?>) result.get("connection")).get("dialect"));
    }

    @Test
    void resolvesOnlyEnabledProfilesBoundToTheCurrentPuppet() {
        Fixture fixture = fixture();
        PuppetDatabaseConnection existing = existing(fixture.persistence(), fixture.crypto());
        when(fixture.mapper().selectById("connection-1")).thenReturn(existing);

        DatabaseConnectionSpec resolved = fixture.profile().resolveActive(
                "user-1", "puppet-1", "connection-1");
        assertEquals("existing-secret", resolved.getPassword());

        DatabaseConnectionProfileException mismatch = assertThrows(
                DatabaseConnectionProfileException.class,
                () -> fixture.profile().resolveActive(
                        "user-1", "puppet-2", "connection-1"));
        assertEquals(DatabaseConnectionProfileException.Kind.FORBIDDEN, mismatch.getKind());

        existing.setStatus(0);
        DatabaseConnectionProfileException disabled = assertThrows(
                DatabaseConnectionProfileException.class,
                () -> fixture.profile().resolveActive(
                        "user-1", "puppet-1", "connection-1"));
        assertEquals(DatabaseConnectionProfileException.Kind.VALIDATION, disabled.getKind());
    }

    @Test
    void deletesWithPuppetScopeAtTheMapperBoundary() {
        Fixture fixture = fixture();
        PuppetDatabaseConnection existing = existing(fixture.persistence(), fixture.crypto());
        when(fixture.mapper().selectById("connection-1")).thenReturn(existing);
        when(fixture.mapper().deleteByIdAndPuppet("connection-1", "puppet-1"))
                .thenReturn(1);

        fixture.profile().delete("user-1", "puppet-1", "connection-1");

        verify(fixture.mapper()).deleteByIdAndPuppet("connection-1", "puppet-1");
    }

    private Fixture fixture() {
        PuppetDatabaseConnectionMapper mapper = mock(PuppetDatabaseConnectionMapper.class);
        DatabaseCredentialCryptoService crypto =
                new DatabaseCredentialCryptoService("profile-test-key", "unused");
        PuppetDatabaseConnectionService persistence =
                new PuppetDatabaseConnectionService(mapper, crypto);
        return new Fixture(mapper, crypto, persistence,
                new DatabaseConnectionProfileService(persistence, new SqlDialectRegistry()));
    }

    private PuppetDatabaseConnection existing(PuppetDatabaseConnectionService persistence,
                                                DatabaseCredentialCryptoService crypto) {
        PuppetDatabaseConnection connection = new PuppetDatabaseConnection();
        connection.setConnectionId("connection-1");
        connection.setConnectionName("inventory");
        connection.setPuppetId("puppet-1");
        connection.setCreateUserId("user-1");
        persistence.applyConnectionSpec(connection,
                DatabaseConnectionSpec.fromMap(
                        mysqlConnection("db.internal", "existing-secret")));
        connection.setPassword(crypto.encrypt("existing-secret"));
        return connection;
    }

    private Map<String, Object> mysqlConnection(String host, String password) {
        return Map.of(
                "dialect", "mysql",
                "connectionMode", "standard",
                "host", host,
                "port", 3306,
                "database", "inventory",
                "username", "app",
                "password", password);
    }

    private record Fixture(PuppetDatabaseConnectionMapper mapper,
                           DatabaseCredentialCryptoService crypto,
                           PuppetDatabaseConnectionService persistence,
                           DatabaseConnectionProfileService profile) {}
}
