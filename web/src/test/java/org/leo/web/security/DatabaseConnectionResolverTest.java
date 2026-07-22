package org.leo.web.security;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.PuppetDatabaseConnection;
import org.leo.core.entity.User;
import org.leo.core.puppet.database.DatabaseConnectionSpec;
import org.leo.service.PuppetDatabaseConnectionService;
import org.leo.web.exception.ApiException;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DatabaseConnectionResolverTest {

    @Test
    void keepsInlineConnectionsIndependentFromPersistence() {
        PuppetDatabaseConnectionService service = mock(PuppetDatabaseConnectionService.class);
        DatabaseConnectionResolver resolver = new DatabaseConnectionResolver(service);
        Map<String, Object> supplied = new LinkedHashMap<String, Object>();
        supplied.put("type", "mysql");
        supplied.put("host", "db.internal");

        Map<String, Object> resolved = resolver.resolve(supplied, "puppet-1", null);

        assertEquals(supplied, resolved);
        assertNotSame(supplied, resolved);
        verifyNoInteractions(service);
    }

    @Test
    void resolvesConnectionReferencesOnlyAfterPuppetAndPermissionChecks() {
        PuppetDatabaseConnectionService service = mock(PuppetDatabaseConnectionService.class);
        DatabaseConnectionResolver resolver = new DatabaseConnectionResolver(service);
        PuppetDatabaseConnection saved = new PuppetDatabaseConnection();
        saved.setConnectionId("connection-1");
        saved.setPuppetId("puppet-1");
        saved.setCreateUserId("owner");
        saved.setStatus(1);
        User owner = user("owner");
        DatabaseConnectionSpec spec = DatabaseConnectionSpec.fromMap(Map.of(
                "type", "mysql", "host", "db.internal", "port", 3306,
                "database", "inventory", "username", "app", "password", "secret"));
        when(service.findById("connection-1")).thenReturn(saved);
        when(service.toActiveConnectionSpec(saved)).thenReturn(spec);

        Map<String, Object> resolved = resolver.resolve(
                Map.of("connectionId", " connection-1 "), "puppet-1", owner);

        assertEquals("db.internal", resolved.get("host"));
        assertEquals("connection-1", resolver.reference(Map.of("connectionId", " connection-1 ")));
    }

    @Test
    void rejectsMissingProfilesCrossPuppetUseAndAnonymousCredentialResolution() {
        PuppetDatabaseConnectionService service = mock(PuppetDatabaseConnectionService.class);
        DatabaseConnectionResolver resolver = new DatabaseConnectionResolver(service);
        PuppetDatabaseConnection saved = new PuppetDatabaseConnection();
        saved.setConnectionId("connection-1");
        saved.setPuppetId("puppet-1");
        saved.setCreateUserId("owner");
        when(service.findById("connection-1")).thenReturn(saved);

        ApiException anonymous = assertThrows(ApiException.class,
                () -> resolver.resolve(Map.of("connectionId", "connection-1"), "puppet-1", null));
        ApiException wrongPuppet = assertThrows(ApiException.class,
                () -> resolver.resolve(Map.of("connectionId", "connection-1"), "puppet-2", user("owner")));
        when(service.findById("missing")).thenReturn(null);
        ApiException missing = assertThrows(ApiException.class,
                () -> resolver.resolve(Map.of("connectionId", "missing"), "puppet-1", user("owner")));

        assertEquals(401, anonymous.getHttpStatus().value());
        assertEquals(403, wrongPuppet.getHttpStatus().value());
        assertEquals(404, missing.getHttpStatus().value());
    }

    private User user(String id) {
        User user = new User();
        user.setUserId(id);
        user.setPrivilege("normal");
        user.setTeamId("team-a");
        return user;
    }
}
