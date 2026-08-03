package org.leo.service.sql.dialect;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DomesticSqlDialectTest {

    @Test
    void dmUsesOwnerSemanticsAndRownumPagination() {
        DmSqlDialect dialect = new DmSqlDialect();

        assertEquals("SELECT BANNER AS version FROM V$VERSION WHERE ROWNUM = 1", dialect.buildTestSql());
        assertTrue(dialect.buildTablesSql("app").contains("t.OWNER = 'APP'"));
        assertTrue(dialect.buildTableColumnsSql("app", "orders").contains("c.TABLE_NAME = 'ORDERS'"));
        String query = dialect.buildQueryTableSql("app", "orders", 2, 20,
                List.of("id"), List.of(Map.of("field", "id", "direction", "ASC")), List.of());
        assertTrue(query.contains("ROWNUM <= 40"));
        assertTrue(query.endsWith("WHERE rn > 20"));
        assertThrows(IllegalArgumentException.class, () -> dialect.buildCreateDatabaseSql("app"));
    }

    @Test
    void kingbaseUsesPostgresqlCompatibleMetadataAndPagination() {
        KingbaseEsDialect dialect = new KingbaseEsDialect();

        assertTrue(dialect.buildDatabasesSql().contains("information_schema.schemata"));
        assertTrue(dialect.buildTablesSql("sales").contains("table_schema = 'sales'"));
        assertTrue(dialect.buildTableColumnsSql("sales", "orders").contains("information_schema.columns"));
        String query = dialect.buildQueryTableSql("sales", "orders", 3, 25,
                List.of(), List.of(), List.of());
        assertTrue(query.endsWith("LIMIT 25 OFFSET 50"));
        assertThrows(IllegalArgumentException.class, () -> dialect.buildCreateDatabaseSql("warehouse"));
    }
}
