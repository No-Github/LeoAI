package org.leo.ai.tools.puppetnode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leo.ai.agent.AiToolContext;
import org.leo.core.entity.Puppet;
import org.leo.core.entity.User;
import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.core.session.PuppetNodeSession;
import org.leo.core.session.PuppetNodeSessionContainer;
import org.leo.service.DatabaseConnectionProfileService;
import org.leo.service.audit.PuppetAuditService;
import org.leo.service.sql.dialect.SqlDialectRegistry;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseConnectionToolsTest {

    private static final String SESSION_ID = "database-tools-session";

    @AfterEach
    void cleanup() {
        AiToolContext.clear();
        PuppetNodeSessionContainer.removeSession(SESSION_ID);
    }

    @Test
    void createsProfilesForTheCurrentSessionUserAndPuppet() {
        DatabaseConnectionProfileService profile =
                mock(DatabaseConnectionProfileService.class);
        PuppetAuditService audit = mock(PuppetAuditService.class);
        DatabaseConnectionTools tools = new DatabaseConnectionTools(
                profile, audit, new SqlDialectRegistry());
        registerSession();
        AiToolContext.setFromMemoryId(SESSION_ID + ":thread-1");
        Map<String, Object> config = Map.of(
                "connectionName", "inventory",
                "connection", Map.of(
                        "dialect", "mysql",
                        "connectionMode", "standard",
                        "host", "db.internal"));
        when(profile.create("user-1", "puppet-1", config))
                .thenReturn(Map.of("connectionId", "connection-1"));

        Map<String, Object> result = tools.createDatabaseConnection(config);

        assertEquals("connection-1", result.get("connectionId"));
        verify(profile).create("user-1", "puppet-1", config);
        verify(audit).logSuccess(eq(SESSION_ID), any(),
                eq("DATABASE_CONNECTION_CREATE"), eq("AI新增数据库配置"),
                eq("create"), any(), eq("AI新增数据库配置成功"));
    }

    @Test
    void deletesOnlyThroughTheCurrentPuppetScope() {
        DatabaseConnectionProfileService profile =
                mock(DatabaseConnectionProfileService.class);
        DatabaseConnectionTools tools = new DatabaseConnectionTools(
                profile, mock(PuppetAuditService.class), new SqlDialectRegistry());
        registerSession();
        AiToolContext.setFromMemoryId(SESSION_ID);

        Map<String, Object> result = tools.deleteDatabaseConnection("connection-1");

        assertEquals(true, result.get("deleted"));
        verify(profile).delete("user-1", "puppet-1", "connection-1");
    }

    @Test
    void exposesTheRegisteredDialectCatalogToTheModel() {
        DatabaseConnectionTools tools = new DatabaseConnectionTools(
                mock(DatabaseConnectionProfileService.class),
                mock(PuppetAuditService.class),
                new SqlDialectRegistry());

        List<Map<String, Object>> catalog = tools.getDatabaseDialectCatalog();
        Set<String> dialects = catalog.stream()
                .map(item -> String.valueOf(item.get("type")))
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                "mysql", "postgresql", "sqlserver", "oracle", "dm", "kingbasees", "sqlite", "generic"),
                dialects);
        Map<String, Object> dm = catalog.stream()
                .filter(item -> "dm".equals(item.get("type")))
                .findFirst()
                .orElseThrow();
        assertEquals(Map.of("java", true, "php", false), dm.get("runtimeSupport"));
    }

    private void registerSession() {
        AbstractPuppetNode node = mock(AbstractPuppetNode.class);
        Puppet puppet = new Puppet();
        puppet.setPuppetId("puppet-1");
        User user = new User();
        user.setUserId("user-1");
        when(node.getPuppet()).thenReturn(puppet);
        when(node.getUser()).thenReturn(user);
        PuppetNodeSession session = new PuppetNodeSession();
        session.setSessionId(SESSION_ID);
        session.setPuppetNode(node);
        session.setCreateByUser("user-1");
        PuppetNodeSessionContainer.addSession(SESSION_ID, session);
    }
}
