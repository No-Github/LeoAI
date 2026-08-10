package org.leo.service.sql.dialect;

import org.leo.service.sql.SqlObjectRef;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class SqlServerDialect extends AbstractSqlDialect {

    public String getType() { return "sqlserver"; }
    public String getName() { return "SQL Server"; }
    public Integer getDefaultPort() { return 1433; }
    public List<String> getNamespaceLevels() { return List.of("catalog", "schema"); }
    public List<Map<String, Object>> getVariants() { return Arrays.<Map<String, Object>>asList(
            variant("default", "SQL Server", "host", "port", "database", "username", "password", "options")); }
    public List<Map<String, Object>> getDataTypes() {
        return Arrays.<Map<String, Object>>asList(
                dataType("INT", null, null, null), dataType("BIGINT", null, null, null),
                dataType("NVARCHAR", 255, null, null), dataType("TEXT", null, null, null),
                dataType("DECIMAL", null, 10, 2), dataType("DATETIME", null, null, null),
                dataType("BIT", null, null, null), dataType("UNIQUEIDENTIFIER", null, null, null)
        );
    }
    public String buildTestSql() { return "SELECT @@VERSION AS version"; }
    public String buildDatabasesSql() {
        return "SELECT name FROM sys.databases WHERE state = 0 AND HAS_DBACCESS(name) = 1 ORDER BY name";
    }
    public String buildTablesSql(SqlObjectRef namespace) {
        String database = namespace == null ? null : namespace.catalog();
        String prefix = isBlank(database) ? "" : escapeIdentifier(database) + ".";
        return "SELECT t.name AS name, s.name AS schema_name, CAST(ep.value AS NVARCHAR(4000)) AS remarks " +
                "FROM " + prefix + "sys.tables t JOIN " + prefix + "sys.schemas s ON s.schema_id = t.schema_id " +
                "LEFT JOIN " + prefix + "sys.extended_properties ep ON ep.class = 1 AND ep.major_id = t.object_id " +
                "AND ep.minor_id = 0 AND ep.name = 'MS_Description' WHERE t.is_ms_shipped = 0 ORDER BY s.name, t.name";
    }
    public String buildTableColumnsSql(SqlObjectRef table) {
        String catalog = table == null ? null : table.catalog();
        String schema = table == null || isBlank(table.schema()) ? "dbo" : table.schema();
        String tableName = table == null ? null : table.name();
        String prefix = isBlank(catalog) ? "" : escapeIdentifier(catalog) + ".";
        return "SELECT c.name AS name, t.name AS type, CASE WHEN c.is_nullable = 1 THEN 'YES' ELSE 'NO' END AS nullable, " +
                "dc.definition AS default_value, CAST(ep.value AS NVARCHAR(4000)) AS remarks, " +
                "CASE WHEN t.name IN ('nchar', 'nvarchar') AND c.max_length > 0 THEN c.max_length / 2 ELSE c.max_length END AS length, " +
                "c.precision AS numeric_precision, c.scale AS numeric_scale, CASE WHEN pk.column_id IS NULL THEN 0 ELSE 1 END AS primary_key " +
                "FROM " + prefix + "sys.columns c JOIN " + prefix + "sys.types t ON c.user_type_id = t.user_type_id " +
                "JOIN " + prefix + "sys.tables tb ON c.object_id = tb.object_id " +
                "JOIN " + prefix + "sys.schemas s ON tb.schema_id = s.schema_id " +
                "LEFT JOIN " + prefix + "sys.default_constraints dc ON dc.parent_object_id = c.object_id AND dc.parent_column_id = c.column_id " +
                "LEFT JOIN " + prefix + "sys.extended_properties ep ON ep.class = 1 AND ep.major_id = c.object_id " +
                "AND ep.minor_id = c.column_id AND ep.name = 'MS_Description' " +
                "LEFT JOIN (SELECT ic.object_id, ic.column_id FROM " + prefix + "sys.indexes i JOIN " + prefix + "sys.index_columns ic " +
                "ON i.object_id = ic.object_id AND i.index_id = ic.index_id WHERE i.is_primary_key = 1) pk ON pk.object_id = c.object_id AND pk.column_id = c.column_id " +
                "WHERE tb.name = " + formatLiteral(tableName) + " AND s.name = " + formatLiteral(schema) + " ORDER BY c.column_id";
    }
    protected String buildQualifiedTable(SqlObjectRef table) {
        String catalog = table == null ? null : table.catalog();
        String schema = table == null || table.schema() == null ? "dbo" : table.schema();
        String name = table == null ? null : table.name();
        String prefix = isBlank(catalog) ? "" : escapeIdentifier(catalog) + ".";
        return prefix + escapeIdentifier(schema) + "." + escapeIdentifier(name);
    }
    protected String buildPaginationSql(String baseSql, int offset, int pageSize) {
        String ordered = baseSql.toLowerCase().contains(" order by ") ? baseSql : baseSql + " ORDER BY 1";
        return ordered + " OFFSET " + offset + " ROWS FETCH NEXT " + pageSize + " ROWS ONLY";
    }
    protected String escapeIdentifier(String identifier) { return "[" + identifier.replace("]", "]]") + "]"; }
}
