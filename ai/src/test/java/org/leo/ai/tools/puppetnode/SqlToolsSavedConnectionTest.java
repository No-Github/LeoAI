package org.leo.ai.tools.puppetnode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leo.ai.agent.AiToolContext;
import org.leo.core.entity.Puppet;
import org.leo.core.entity.User;
import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.core.puppet.capability.SqlCapable;
import org.leo.core.puppet.database.DatabaseConnectionSpec;
import org.leo.core.runtime.CapabilitySet;
import org.leo.core.session.PuppetNodeSession;
import org.leo.core.session.PuppetNodeSessionContainer;
import org.leo.service.DatabaseConnectionProfileService;
import org.leo.service.audit.PuppetAuditService;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class SqlToolsSavedConnectionTest {

    private static final String SESSION_ID = "sql-saved-connection-session";

    @AfterEach
    void cleanup() {
        AiToolContext.clear();
        PuppetNodeSessionContainer.removeSession(SESSION_ID);
    }

    @Test
    void resolvesSavedConnectionIdBeforeExecutingQuery() throws Exception {
        Fixture fixture = fixture();
        when(fixture.sqlNode().executeSql(fixture.spec(), "SELECT 1"))
                .thenReturn(Map.of("code", 200, "rows", 1));

        Map<String, Object> result = fixture.tools().querySql(
                Map.of("connectionId", "connection-1"), "SELECT 1");

        assertEquals(200, result.get("code"));
        verify(fixture.profiles()).resolveActive("user-1", "puppet-1", "connection-1");
        verify(fixture.sqlNode()).executeSql(fixture.spec(), "SELECT 1");
    }

    @Test
    void allowsOneReadOnlyStatementWithCommentsAndQuotedSemicolons() throws Exception {
        Fixture fixture = fixture();
        String sql = "/* evidence */ SELECT ';' AS marker; -- trailing comment";
        when(fixture.sqlNode().executeSql(fixture.spec(), sql))
                .thenReturn(Map.of("code", 200));

        Map<String, Object> result = fixture.tools().querySql(
                Map.of("connectionId", "connection-1"), sql);

        assertEquals(200, result.get("code"));
        verify(fixture.sqlNode()).executeSql(fixture.spec(), sql);
    }

    @Test
    void rejectsWritableCteBeforeResolvingOrExecutingAConnection() throws Exception {
        Fixture fixture = fixture();
        String sql = "WITH deleted AS (DELETE FROM users RETURNING *) SELECT * FROM deleted";

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> fixture.tools().querySql(Map.of("connectionId", "connection-1"), sql));

        assertTrue(error.getMessage().contains("WITH/CTE"));
        verify(fixture.profiles(), never()).resolveActive(
                "user-1", "puppet-1", "connection-1");
        verify(fixture.sqlNode(), never()).executeSql(fixture.spec(), sql);
    }

    @Test
    void rejectsMultipleStatementsAndUnprovenReadOnlyKeywords() {
        Fixture fixture = fixture();

        IllegalArgumentException multiple = assertThrows(IllegalArgumentException.class,
                () -> fixture.tools().querySql(Map.of(), "SELECT 1; DELETE FROM users"));
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> fixture.tools().querySql(Map.of(), "VACUUM"));
        IllegalArgumentException explain = assertThrows(IllegalArgumentException.class,
                () -> fixture.tools().querySql(Map.of(), "EXPLAIN ANALYZE DELETE FROM users"));
        IllegalArgumentException pragma = assertThrows(IllegalArgumentException.class,
                () -> fixture.tools().querySql(Map.of(), "PRAGMA journal_mode=WAL"));

        assertTrue(multiple.getMessage().contains("只允许一条 SQL"));
        assertTrue(unknown.getMessage().contains("VACUUM"));
        assertTrue(explain.getMessage().contains("EXPLAIN"));
        assertTrue(pragma.getMessage().contains("PRAGMA"));
    }

    @Test
    void rejectsUnterminatedQuotedContent() {
        Fixture fixture = fixture();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> fixture.tools().querySql(Map.of(), "SELECT 'unterminated"));

        assertTrue(error.getMessage().contains("未闭合"));
    }

    private Fixture fixture() {
        DatabaseConnectionProfileService profiles =
                mock(DatabaseConnectionProfileService.class);
        PuppetAuditService audit = mock(PuppetAuditService.class);
        SqlTools tools = new SqlTools(audit, profiles);
        AbstractPuppetNode node = mock(AbstractPuppetNode.class,
                withSettings().extraInterfaces(SqlCapable.class));
        SqlCapable sqlNode = (SqlCapable) node;
        Puppet puppet = new Puppet();
        puppet.setPuppetId("puppet-1");
        User user = new User();
        user.setUserId("user-1");
        when(node.getPuppet()).thenReturn(puppet);
        when(node.getUser()).thenReturn(user);
        when(node.getCapabilitySet()).thenReturn(CapabilitySet.empty());
        DatabaseConnectionSpec spec = DatabaseConnectionSpec.fromMap(Map.of(
                "dialect", "mysql",
                "connectionMode", "standard",
                "host", "db.internal",
                "port", 3306,
                "database", "inventory",
                "username", "app",
                "password", "secret"));
        when(profiles.resolveActive("user-1", "puppet-1", "connection-1"))
                .thenReturn(spec);
        PuppetNodeSession session = new PuppetNodeSession();
        session.setSessionId(SESSION_ID);
        session.setPuppetNode(node);
        session.setCreateByUser("user-1");
        PuppetNodeSessionContainer.addSession(SESSION_ID, session);
        AiToolContext.setFromMemoryId(SESSION_ID);
        return new Fixture(tools, profiles, sqlNode, spec);
    }

    private record Fixture(SqlTools tools,
                           DatabaseConnectionProfileService profiles,
                           SqlCapable sqlNode,
                           DatabaseConnectionSpec spec) {}
}
