package org.leo.core.init;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseInitializerFreshStartTest {

    @TempDir
    Path tempDir;

    @Test
    void createsCurrentSchemaAndCanRunTwice() throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("fresh.db"));
        DatabaseInitializer initializer = new DatabaseInitializer(dataSource);

        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("sql/schema.sql"));
        }
        initializer.run();
        initializer.run();

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            assertEquals(1, scalar(statement,
                    "SELECT COUNT(*) FROM pragma_table_info('ai_messages') WHERE name='nodes_json'"));
            assertEquals(1, scalar(statement,
                    "SELECT COUNT(*) FROM pragma_table_info('ai_runs') WHERE name='runtime_json'"));
            assertEquals(1, scalar(statement,
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='uk_users_user_name_nocase'"));
            assertEquals(1, scalar(statement,
                    "SELECT COUNT(*) FROM system_configs WHERE config_key='system.version' AND config_value='1.0.0'"));

            SQLException rejected = assertThrows(SQLException.class, () -> statement.executeUpdate("""
                    INSERT INTO puppets
                      (puppet_id, puppet_name, parent_puppet_id, create_by_user_id, conn_link,
                       req_disguise_id, resp_disguise_id, permission, create_time, update_time)
                    VALUES ('p-1', 'test', 'root', 'admin', '/', 'request', 'response', 'protected',
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """));
            assertTrue(rejected.getMessage().contains("CHECK constraint"));
        }
    }

    private int scalar(Statement statement, String sql) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }
}
