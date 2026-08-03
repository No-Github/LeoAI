package org.leo.service.sql.dialect;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Conservative SQL dialect for databases without a built-in management
 * dialect. Raw SQL and connection tests work; vendor metadata operations are
 * intentionally unavailable until a dedicated dialect is added.
 */
public final class GenericSqlDialect extends AbstractSqlDialect {

    public String getType() { return "generic"; }
    public String getName() { return "Generic SQL"; }
    public Integer getDefaultPort() { return null; }
    public List<Map<String, Object>> getVariants() {
        return Arrays.<Map<String, Object>>asList(
                variant("custom", "自定义运行时连接", "username", "password", "runtimeOptions"));
    }
    public List<Map<String, Object>> getDataTypes() {
        return Arrays.<Map<String, Object>>asList(
                dataType("INTEGER", null, null, null),
                dataType("BIGINT", null, null, null),
                dataType("VARCHAR", 255, null, null),
                dataType("DECIMAL", null, 10, 2),
                dataType("DATE", null, null, null),
                dataType("TIMESTAMP", null, null, null),
                dataType("BLOB", null, null, null)
        );
    }
    public SqlDialectCapabilities getCapabilities() { return SqlDialectCapabilities.rawOnly(); }
    public String buildTestSql() { return "SELECT 1 AS version"; }
    public String buildDatabasesSql() { throw unsupported("获取数据库列表"); }
    public String buildTablesSql(String database) { throw unsupported("获取数据表列表"); }
    public String buildTableColumnsSql(String database, String table) { throw unsupported("获取字段列表"); }
    public String buildCountSql(String database, String table, List<Map<String, Object>> filters) {
        throw unsupported("结构化表查询");
    }
    public String buildQueryTableSql(String database,
                                     String table,
                                     int page,
                                     int pageSize,
                                     List<String> columns,
                                     List<Map<String, Object>> orderBy,
                                     List<Map<String, Object>> filters) {
        throw unsupported("结构化表查询");
    }
    public String buildCreateTableSql(String database, String table, List<Map<String, Object>> columns) {
        throw unsupported("创建数据表");
    }
    public String buildCreateDatabaseSql(String database) { throw unsupported("创建数据库"); }
    public String buildInsertSql(String database, String table, Map<String, Object> row) {
        throw unsupported("结构化数据写入");
    }
    public String buildUpdateSql(String database,
                                 String table,
                                 Map<String, Object> where,
                                 Map<String, Object> update) {
        throw unsupported("结构化数据更新");
    }
    public String buildDeleteSql(String database, String table, Map<String, Object> where) {
        throw unsupported("结构化数据删除");
    }
    protected String buildQualifiedTable(String database, String table) {
        if (isBlank(database)) return escapeIdentifier(table);
        return escapeIdentifier(database) + "." + escapeIdentifier(table);
    }
    protected String buildPaginationSql(String baseSql, int offset, int pageSize) {
        return baseSql + " OFFSET " + offset + " ROWS FETCH NEXT " + pageSize + " ROWS ONLY";
    }
    protected String escapeIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
    private IllegalArgumentException unsupported(String operation) {
        return new IllegalArgumentException("Generic SQL 方言不支持" + operation + "，请直接执行厂商 SQL");
    }
}
