package org.leo.web.controller.puppetnode.sql;

import org.junit.jupiter.api.Test;
import org.leo.service.PuppetDatabaseConnectionService;
import org.leo.service.sql.PuppetNodeSqlService;
import org.leo.service.sql.SqlExportService;
import org.leo.service.sql.dialect.SqlDialectRegistry;
import org.leo.web.security.DatabaseConnectionResolver;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class PuppetNodeSQLControllerDialectCatalogTest {

    @Test
    void exposesTheRegistryCatalogAndGenericCapabilityBoundary() {
        PuppetNodeSQLController controller = new PuppetNodeSQLController(
                new PuppetNodeSqlService(new SqlDialectRegistry()),
                mock(SqlExportService.class),
                mock(PuppetDatabaseConnectionService.class),
                mock(DatabaseConnectionResolver.class));

        Map<String, Object> response = controller.getDialects();

        assertEquals(200, response.get("code"));
        List<?> catalog = (List<?>) response.get("data");
        Map<?, ?> generic = catalog.stream()
                .map(Map.class::cast)
                .filter(item -> "generic".equals(item.get("type")))
                .findFirst()
                .orElseThrow();
        assertEquals(List.of("custom"), generic.get("connectionModes"));
        Map<?, ?> capabilities = (Map<?, ?>) generic.get("capabilities");
        assertEquals(true, capabilities.get("rawSql"));
        assertEquals(false, capabilities.get("structuredQuery"));
        assertEquals(false, capabilities.get("createDatabase"));
        assertTrue(((List<?>) generic.get("variants")).size() > 0);

        Map<?, ?> dm = catalog.stream()
                .map(Map.class::cast)
                .filter(item -> "dm".equals(item.get("type")))
                .findFirst()
                .orElseThrow();
        assertEquals(5236, dm.get("defaultPort"));
        assertEquals(Map.of("java", true, "php", false), dm.get("runtimeSupport"));
        assertTrue(((List<?>) dm.get("dataTypes")).stream()
                .map(Map.class::cast)
                .anyMatch(type -> "VARCHAR2".equals(type.get("type"))));

        Map<?, ?> kingbase = catalog.stream()
                .map(Map.class::cast)
                .filter(item -> "kingbasees".equals(item.get("type")))
                .findFirst()
                .orElseThrow();
        assertEquals(54321, kingbase.get("defaultPort"));
        assertEquals(Map.of("java", true, "php", false), kingbase.get("runtimeSupport"));
    }
}
