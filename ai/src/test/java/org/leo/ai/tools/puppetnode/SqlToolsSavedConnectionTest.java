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
import static org.mockito.Mockito.mock;
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
        when(sqlNode.executeSql(spec, "SELECT 1"))
                .thenReturn(Map.of("code", 200, "rows", 1));
        PuppetNodeSession session = new PuppetNodeSession();
        session.setSessionId(SESSION_ID);
        session.setPuppetNode(node);
        session.setCreateByUser("user-1");
        PuppetNodeSessionContainer.addSession(SESSION_ID, session);
        AiToolContext.setFromMemoryId(SESSION_ID);

        Map<String, Object> result = tools.querySql(
                Map.of("connectionId", "connection-1"), "SELECT 1");

        assertEquals(200, result.get("code"));
        verify(profiles).resolveActive("user-1", "puppet-1", "connection-1");
        verify(sqlNode).executeSql(spec, "SELECT 1");
    }
}
