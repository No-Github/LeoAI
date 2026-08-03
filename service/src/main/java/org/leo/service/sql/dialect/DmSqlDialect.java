package org.leo.service.sql.dialect;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Dameng DM dialect. The connection database field represents an owner/schema. */
public class DmSqlDialect extends AbstractSqlDialect {

    public String getType() { return "dm"; }
    public String getName() { return "达梦 DM"; }
    public Integer getDefaultPort() { return 5236; }
    public List<Map<String, Object>> getVariants() {
        return Arrays.<Map<String, Object>>asList(
                variant("default", "达梦 DM", "host", "port", "database", "username", "password", "options"));
    }
    public List<Map<String, Object>> getDataTypes() {
        return Arrays.<Map<String, Object>>asList(
                dataType("INT", null, null, null), dataType("BIGINT", null, null, null),
                dataType("NUMBER", null, 10, 2), dataType("VARCHAR", 255, null, null),
                dataType("VARCHAR2", 255, null, null), dataType("CHAR", 10, null, null),
                dataType("DATE", null, null, null), dataType("TIMESTAMP", null, null, null),
                dataType("CLOB", null, null, null), dataType("BLOB", null, null, null)
        );
    }
    public SqlDialectCapabilities getCapabilities() { return SqlDialectCapabilities.managed(false); }
    public Map<String, Boolean> getRuntimeSupport() { return Map.of("java", true, "php", false); }
    public String buildTestSql() { return "SELECT BANNER AS version FROM V$VERSION WHERE ROWNUM = 1"; }
    public String buildDatabasesSql() { return "SELECT USERNAME AS name FROM ALL_USERS ORDER BY USERNAME"; }
    public String buildCreateDatabaseSql(String database) {
        throw new IllegalArgumentException("dm 暂不支持通过该接口创建数据库实例");
    }
    public String buildTablesSql(String database) {
        String ownerExpr = isBlank(database) ? "USER" : formatLiteral(database.toUpperCase());
        return "SELECT t.TABLE_NAME AS name, t.OWNER AS schema_name, COALESCE(c.COMMENTS, '') AS comment " +
                "FROM ALL_TABLES t LEFT JOIN ALL_TAB_COMMENTS c ON c.OWNER = t.OWNER AND c.TABLE_NAME = t.TABLE_NAME " +
                "WHERE t.OWNER = " + ownerExpr + " ORDER BY t.TABLE_NAME";
    }
    public String buildTableColumnsSql(String database, String table) {
        String ownerExpr = isBlank(database) ? "USER" : formatLiteral(database.toUpperCase());
        return "SELECT c.COLUMN_NAME AS name, c.DATA_TYPE AS type, CASE WHEN c.NULLABLE = 'Y' THEN 'YES' ELSE 'NO' END AS nullable, " +
                "c.DATA_DEFAULT AS default_value, COALESCE(cc.COMMENTS, '') AS comment, c.CHAR_LENGTH AS length, " +
                "c.DATA_PRECISION AS numeric_precision, c.DATA_SCALE AS numeric_scale, " +
                "CASE WHEN pk.COLUMN_NAME IS NULL THEN 0 ELSE 1 END AS primary_key FROM ALL_TAB_COLUMNS c " +
                "LEFT JOIN ALL_COL_COMMENTS cc ON cc.OWNER = c.OWNER AND cc.TABLE_NAME = c.TABLE_NAME AND cc.COLUMN_NAME = c.COLUMN_NAME " +
                "LEFT JOIN (SELECT acc.OWNER, acc.TABLE_NAME, acc.COLUMN_NAME FROM ALL_CONSTRAINTS ac JOIN ALL_CONS_COLUMNS acc " +
                "ON ac.OWNER = acc.OWNER AND ac.CONSTRAINT_NAME = acc.CONSTRAINT_NAME WHERE ac.CONSTRAINT_TYPE = 'P') pk " +
                "ON pk.OWNER = c.OWNER AND pk.TABLE_NAME = c.TABLE_NAME AND pk.COLUMN_NAME = c.COLUMN_NAME " +
                "WHERE c.OWNER = " + ownerExpr + " AND c.TABLE_NAME = " + formatLiteral(table.toUpperCase()) + " ORDER BY c.COLUMN_ID";
    }
    protected String buildQualifiedTable(String database, String table) {
        if (isBlank(database)) return escapeIdentifier(table.toUpperCase());
        return escapeIdentifier(database.toUpperCase()) + "." + escapeIdentifier(table.toUpperCase());
    }
    protected String buildPaginationSql(String baseSql, int offset, int pageSize) {
        return "SELECT * FROM (SELECT inner_query.*, ROWNUM rn FROM (" + baseSql + ") inner_query WHERE ROWNUM <= "
                + (offset + pageSize) + ") WHERE rn > " + offset;
    }
    protected String escapeIdentifier(String identifier) { return "\"" + identifier.replace("\"", "\"\"") + "\""; }
}
