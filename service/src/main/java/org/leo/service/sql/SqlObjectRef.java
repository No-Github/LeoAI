package org.leo.service.sql;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runtime-neutral reference to a database object.
 *
 * <p>Catalog, schema and object name are kept separately so database vendors
 * do not have to overload a single "database" string with different meanings.</p>
 */
public record SqlObjectRef(String catalog, String schema, String name, String kind) {

    public SqlObjectRef {
        catalog = normalize(catalog);
        schema = normalize(schema);
        name = normalize(name);
        kind = normalize(kind);
    }

    public static SqlObjectRef catalog(String name) {
        return new SqlObjectRef(name, null, null, "catalog");
    }

    public static SqlObjectRef schema(String name) {
        return new SqlObjectRef(null, name, null, "schema");
    }

    public static SqlObjectRef table(String catalog, String schema, String name) {
        return new SqlObjectRef(catalog, schema, name, "table");
    }

    public static SqlObjectRef fromMap(Map<?, ?> value) {
        if (value == null || value.isEmpty()) return null;
        SqlObjectRef ref = new SqlObjectRef(
                stringValue(value.get("catalog")),
                stringValue(value.get("schema")),
                stringValue(value.get("name")),
                stringValue(value.get("kind")));
        return ref.catalog == null && ref.schema == null && ref.name == null ? null : ref;
    }

    public String namespace() {
        return schema != null ? schema : catalog;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (catalog != null) result.put("catalog", catalog);
        if (schema != null) result.put("schema", schema);
        if (name != null) result.put("name", name);
        if (kind != null) result.put("kind", kind);
        return result;
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
