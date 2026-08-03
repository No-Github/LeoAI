package org.leo.service.sql.dialect;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit feature matrix for one managed SQL dialect. */
public record SqlDialectCapabilities(
        boolean testConnection,
        boolean rawSql,
        boolean listDatabases,
        boolean listTables,
        boolean listColumns,
        boolean structuredQuery,
        boolean createDatabase,
        boolean createTable,
        boolean insert,
        boolean update,
        boolean delete,
        boolean stableOffsetPagination,
        boolean keysetPagination,
        boolean exportTable,
        boolean exportDatabase,
        boolean exportStructure) {

    public static SqlDialectCapabilities managed(boolean createDatabase) {
        return new SqlDialectCapabilities(
                true, true, true, true, true, true, createDatabase,
                true, true, true, true, false, false, true, true, false);
    }

    public static SqlDialectCapabilities rawOnly() {
        return new SqlDialectCapabilities(
                true, true, false, false, false, false, false,
                false, false, false, false, false, false, false, false, false);
    }

    public Map<String, Boolean> toMap() {
        Map<String, Boolean> result = new LinkedHashMap<String, Boolean>();
        result.put("testConnection", testConnection);
        result.put("rawSql", rawSql);
        result.put("listDatabases", listDatabases);
        result.put("listTables", listTables);
        result.put("listColumns", listColumns);
        result.put("structuredQuery", structuredQuery);
        result.put("createDatabase", createDatabase);
        result.put("createTable", createTable);
        result.put("insert", insert);
        result.put("update", update);
        result.put("delete", delete);
        result.put("stableOffsetPagination", stableOffsetPagination);
        result.put("keysetPagination", keysetPagination);
        result.put("exportTable", exportTable);
        result.put("exportDatabase", exportDatabase);
        result.put("exportStructure", exportStructure);
        return Collections.unmodifiableMap(result);
    }
}
