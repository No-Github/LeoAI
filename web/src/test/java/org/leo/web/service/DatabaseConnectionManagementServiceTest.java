package org.leo.web.service;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.PuppetDatabaseConnection;
import org.leo.core.entity.User;
import org.leo.core.puppet.database.DatabaseConnectionSpec;
import org.leo.dao.mapper.PuppetDatabaseConnectionMapper;
import org.leo.service.PuppetDatabaseConnectionService;
import org.leo.service.security.DatabaseCredentialCryptoService;
import org.leo.web.exception.ApiException;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseConnectionManagementServiceTest {

    @Test
    void savesNewProfilesThroughOneValidatedManagementBoundary() {
        PuppetDatabaseConnectionMapper mapper = mock(PuppetDatabaseConnectionMapper.class);
        DatabaseCredentialCryptoService crypto = new DatabaseCredentialCryptoService("management-key", "unused");
        PuppetDatabaseConnectionService persistence = new PuppetDatabaseConnectionService(mapper, crypto);
        DatabaseConnectionManagementService management =
                new DatabaseConnectionManagementService(persistence);
        doAnswer(invocation -> {
            PuppetDatabaseConnection saved = invocation.getArgument(0);
            assertEquals("team-a", saved.getTeamId());
            assertEquals("team", saved.getScope());
            assertTrue(crypto.isEncrypted(saved.getPassword()));
            return 1;
        }).when(mapper).insert(any(PuppetDatabaseConnection.class));

        Map<String, Object> result = management.save(user("owner", "normal", "team-a"),
                "puppet-1", params(null, "team", "secret"));

        assertEquals("inventory", result.get("connectionName"));
        assertEquals("team", result.get("scope"));
        assertEquals(Boolean.TRUE, result.get("canManage"));
        assertTrue(!String.valueOf(result).contains("secret"));
    }

    @Test
    void updatesUseTheOwnersCurrentTeamAndPreserveAnOmittedPassword() {
        PuppetDatabaseConnectionMapper mapper = mock(PuppetDatabaseConnectionMapper.class);
        DatabaseCredentialCryptoService crypto = new DatabaseCredentialCryptoService("management-key", "unused");
        PuppetDatabaseConnectionService persistence = new PuppetDatabaseConnectionService(mapper, crypto);
        DatabaseConnectionManagementService management =
                new DatabaseConnectionManagementService(persistence);
        PuppetDatabaseConnection existing = existing(persistence, crypto);
        when(mapper.selectById("connection-1")).thenReturn(existing);
        when(mapper.update(any(PuppetDatabaseConnection.class))).thenReturn(1);

        Map<String, Object> result = management.save(user("owner", "normal", "new-team"),
                "puppet-1", params("connection-1", "team", ""));

        assertEquals("new-team", existing.getTeamId());
        assertEquals("team", existing.getScope());
        assertEquals("existing-secret", persistence.toConnectionSpec(existing).getPassword());
        assertEquals("team", result.get("scope"));
    }

    @Test
    void teamLeadersCannotPrivatizeProfilesTheyDoNotOwn() {
        PuppetDatabaseConnectionMapper mapper = mock(PuppetDatabaseConnectionMapper.class);
        DatabaseCredentialCryptoService crypto = new DatabaseCredentialCryptoService("management-key", "unused");
        PuppetDatabaseConnectionService persistence = new PuppetDatabaseConnectionService(mapper, crypto);
        DatabaseConnectionManagementService management =
                new DatabaseConnectionManagementService(persistence);
        PuppetDatabaseConnection existing = existing(persistence, crypto);
        existing.setScope("team");
        existing.setTeamId("team-a");
        when(mapper.selectById("connection-1")).thenReturn(existing);

        ApiException error = assertThrows(ApiException.class,
                () -> management.save(user("leader", "leader", "team-a"),
                        "puppet-1", params("connection-1", "private", "")));

        assertEquals(403, error.getHttpStatus().value());
    }

    private PuppetDatabaseConnection existing(PuppetDatabaseConnectionService persistence,
                                                DatabaseCredentialCryptoService crypto) {
        PuppetDatabaseConnection connection = new PuppetDatabaseConnection();
        connection.setConnectionId("connection-1");
        connection.setConnectionName("inventory");
        connection.setPuppetId("puppet-1");
        connection.setCreateUserId("owner");
        connection.setTeamId("old-team");
        persistence.applyConnectionSpec(connection, DatabaseConnectionSpec.fromMap(Map.of(
                "type", "mysql", "host", "db.internal", "port", 3306,
                "database", "inventory", "username", "app", "password", "existing-secret")));
        connection.setPassword(crypto.encrypt("existing-secret"));
        return connection;
    }

    private Map<String, Object> params(String connectionId, String scope, String password) {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        if (connectionId != null) params.put("connectionId", connectionId);
        params.put("connectionName", "inventory");
        params.put("scope", scope);
        params.put("connection", Map.of(
                "type", "mysql", "host", "db.internal", "port", 3306,
                "database", "inventory", "username", "app", "password", password));
        return params;
    }

    private User user(String id, String privilege, String teamId) {
        User user = new User();
        user.setUserId(id);
        user.setPrivilege(privilege);
        user.setTeamId(teamId);
        return user;
    }
}
