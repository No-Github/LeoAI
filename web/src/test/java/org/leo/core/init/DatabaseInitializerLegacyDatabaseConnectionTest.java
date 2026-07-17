package org.leo.core.init;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leo.core.util.json.PortableJsonCodec;
import org.sqlite.SQLiteDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseInitializerLegacyDatabaseConnectionTest {

    @TempDir
    Path tempDir;

    @Test
    void migratesHistoricalProfilesToRuntimeNeutralTableAndRemovesSourceTable() throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("migration.db"));
        createHistoricalTable(dataSource);

        new DatabaseInitializer(dataSource).run();

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            try (ResultSet row = statement.executeQuery(
                    "SELECT * FROM puppet_database_connections WHERE connection_id='connection-1'")) {
                assertTrue(row.next());
                assertEquals("inventory", row.getString("connection_name"));
                assertEquals("mysql", row.getString("db_type"));
                assertEquals("private", row.getString("scope"));
                assertEquals("historical-secret", row.getString("password"));
                Map<String, Object> spec = PortableJsonCodec.decode(
                        row.getString("connection_spec").getBytes(StandardCharsets.UTF_8));
                assertEquals("db.internal", spec.get("host"));
                assertEquals(3307L, ((Number) spec.get("port")).longValue());
                assertEquals("inventory", spec.get("database"));
                assertFalse(spec.containsKey("password"));
                Map<?, ?> javaOptions = (Map<?, ?>) ((Map<?, ?>) spec.get("nativeOptions")).get("java");
                assertEquals("jdbc:mysql://db.internal:3307/inventory", javaOptions.get("jdbcUrl"));
            }
            try (ResultSet row = statement.executeQuery(
                    "SELECT scope FROM puppet_database_connections WHERE connection_id='connection-2'")) {
                assertTrue(row.next());
                assertEquals("team", row.getString("scope"));
            }
            try (ResultSet sourceTable = statement.executeQuery(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='puppet_jdbc'")) {
                assertTrue(sourceTable.next());
                assertEquals(0, sourceTable.getInt(1));
            }
        }
    }

    @Test
    void addsScopeToExistingRuntimeNeutralProfiles() throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("scope-migration.db"));
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE puppet_database_connections ("
                    + "connection_id TEXT PRIMARY KEY, connection_name TEXT, puppet_id TEXT, db_type TEXT, "
                    + "connection_spec TEXT, username TEXT, password TEXT, status INTEGER, test_status INTEGER, "
                    + "last_test_time TEXT, last_test_message TEXT, max_connections INTEGER, timeout_seconds INTEGER, "
                    + "create_user_id TEXT, team_id TEXT, is_public INTEGER, create_time TEXT, update_time TEXT, "
                    + "description TEXT, remark TEXT)");
            statement.execute("INSERT INTO puppet_database_connections (connection_id, connection_name, puppet_id, "
                    + "db_type, connection_spec, create_user_id, is_public, create_time, update_time) VALUES "
                    + "('private-1','private','puppet-1','mysql','{}','user-1',0,datetime('now'),datetime('now')),"
                    + "('team-1','team','puppet-1','mysql','{}','user-1',1,datetime('now'),datetime('now'))");
        }

        new DatabaseInitializer(dataSource).run();

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT connection_id, scope FROM puppet_database_connections ORDER BY connection_id")) {
            assertTrue(rows.next());
            assertEquals("private", rows.getString("scope"));
            assertTrue(rows.next());
            assertEquals("team", rows.getString("scope"));
        }
    }

    private void createHistoricalTable(SQLiteDataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE puppet_jdbc ("
                    + "conn_id TEXT PRIMARY KEY, conn_name TEXT, puppet_id TEXT, db_type TEXT, host TEXT, "
                    + "port INTEGER, database_name TEXT, username TEXT, password TEXT, url_template TEXT, "
                    + "jdbc_url TEXT, driver_class TEXT, connection_params TEXT, status INTEGER, "
                    + "test_status INTEGER, last_test_time TEXT, last_test_message TEXT, max_connections INTEGER, "
                    + "timeout_seconds INTEGER, create_user_id TEXT, team_id TEXT, is_public INTEGER, "
                    + "create_time TEXT, update_time TEXT, description TEXT, remark TEXT)");
            statement.execute("INSERT INTO puppet_jdbc VALUES ("
                    + "'connection-1','inventory','puppet-1','mysql','db.internal',3307,'inventory','app',"
                    + "'historical-secret',NULL,'jdbc:mysql://db.internal:3307/inventory','com.mysql.cj.jdbc.Driver',"
                    + "NULL,1,0,NULL,NULL,10,45,'user-1','team-1',0,datetime('now'),datetime('now'),NULL,NULL)");
            statement.execute("INSERT INTO puppet_jdbc VALUES ("
                    + "'connection-2','shared','puppet-1','mysql','db.internal',3307,'shared','app',"
                    + "'shared-secret',NULL,NULL,NULL,NULL,1,0,NULL,NULL,10,45,'user-2','team-1',1,"
                    + "datetime('now'),datetime('now'),NULL,NULL)");
        }
    }
}
