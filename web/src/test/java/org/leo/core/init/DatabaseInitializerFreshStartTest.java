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
                    "SELECT COUNT(*) FROM pragma_table_info('ai_runs') WHERE name='trace_id'"));
            assertEquals(1, scalar(statement,
                    "SELECT COUNT(*) FROM pragma_table_info('ai_runs') WHERE name='trace_json'"));
            assertEquals(1, scalar(statement,
                    "SELECT COUNT(*) FROM pragma_table_info('ai_messages') WHERE name='turn_id'"));
            assertEquals(1, scalar(statement,
                    "SELECT COUNT(*) FROM pragma_table_info('ai_messages') WHERE name='run_id'"));
            assertEquals(1, scalar(statement,
                    "SELECT COUNT(*) FROM pragma_table_info('ai_messages') WHERE name='message_seq'"));
            assertEquals(1, scalar(statement,
                    "SELECT COUNT(*) FROM pragma_table_info('ai_messages') WHERE name='status'"));
            assertEquals(1, scalar(statement,
                    "SELECT COUNT(*) FROM pragma_table_info('ai_runs') WHERE name='turn_id'"));
            assertEquals(1, scalar(statement,
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='ai_turns'"));
            assertEquals(1, scalar(statement,
                    "SELECT COUNT(*) FROM pragma_table_info('ai_turns') "
                            + "WHERE name='client_user_message_id'"));
            assertEquals(1, scalar(statement,
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='index' "
                            + "AND name='uk_ai_turns_active_thread'"));
            assertEquals(1, scalar(statement,
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' "
                            + "AND name='ai_thread_leases'"));
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

    @Test
    void rejectsLegacyAiSchemaWithActionableMessage() throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("legacy.db"));
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE ai_messages (
                        message_id VARCHAR(64) PRIMARY KEY,
                        thread_id VARCHAR(64) NOT NULL,
                        role VARCHAR(32) NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE ai_runs (
                        run_id VARCHAR(64) PRIMARY KEY,
                        thread_id VARCHAR(64) NOT NULL,
                        status VARCHAR(32) NOT NULL
                    )
                    """);
        }

        IllegalStateException error = assertThrows(
                IllegalStateException.class, () -> new DatabaseInitializer(dataSource).run());

        assertTrue(error.getMessage().contains("不兼容旧 AI 对话数据"));
    }

    @Test
    void rebuildsOnlyLegacyDisposableEventJournal() throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("legacy-events.db"));
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(
                    connection, new ClassPathResource("sql/schema.sql"));
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("DROP TABLE ai_events");
                statement.executeUpdate("""
                        CREATE TABLE ai_events (
                            event_id VARCHAR(64) PRIMARY KEY,
                            run_id VARCHAR(64),
                            thread_id VARCHAR(64) NOT NULL,
                            event_seq INTEGER NOT NULL,
                            timestamp INTEGER NOT NULL,
                            name VARCHAR(64) NOT NULL,
                            data_json TEXT
                        )
                        """);
            }
        }

        new DatabaseInitializer(dataSource).run();

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            assertEquals(1, scalar(statement,
                    "SELECT COUNT(*) FROM pragma_table_info('ai_events') WHERE name='turn_id'"));
            assertEquals(1, scalar(statement,
                    "SELECT COUNT(*) FROM pragma_table_info('ai_events') WHERE name='item_id'"));
            assertEquals(1, scalar(statement,
                    "SELECT COUNT(*) FROM pragma_table_info('ai_events') "
                            + "WHERE name='subagent_invocation_id'"));
        }
    }

    private int scalar(Statement statement, String sql) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }
}
