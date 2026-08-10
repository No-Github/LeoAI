package org.leo.service.sql;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqlExportServiceObjectRefTest {

    @Test
    void roundTripsRefsStoredInResumableExportTasks() {
        List<SqlObjectRef> refs = List.of(
                SqlObjectRef.table("app", "sales", "orders"),
                SqlObjectRef.table("app", "crm", "customers"));

        List<SqlObjectRef> restored = SqlExportService.refListValue(
                SqlExportService.toRefMaps(refs));

        assertEquals(refs, restored);
    }

    @Test
    void includesSchemaInExportEntryNamesToAvoidCollisions() {
        assertEquals("app_sales_orders", SqlExportService.exportObjectName(
                SqlObjectRef.table("app", "sales", "orders")));
        assertEquals("app_crm_orders", SqlExportService.exportObjectName(
                SqlObjectRef.table("app", "crm", "orders")));
    }
}
