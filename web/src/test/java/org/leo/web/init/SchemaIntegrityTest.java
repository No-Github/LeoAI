package org.leo.web.init;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaIntegrityTest {

    @Test
    void freshSchemaEnforcesDomainChecksAndAiCascadeRelationships() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys=ON");
            }
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("sql/schema.sql"));

            assertThrows(SQLException.class, () -> {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("""
                            INSERT INTO users
                              (user_id, user_name, password, privilege, status, login_count, create_time, update_time)
                            VALUES ('invalid', 'invalid', 'hash', 'superuser', 1, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                            """);
                }
            });

            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO ai_threads
                          (thread_id, scope, title, created_at, last_active_at)
                        VALUES ('thread-1', 'platform', 'test', 1, 1)
                        """);
                statement.executeUpdate("""
                        INSERT INTO ai_messages
                          (message_id, thread_id, role, content, timestamp)
                        VALUES ('message-1', 'thread-1', 'user', 'hello', 1)
                        """);
                statement.executeUpdate("DELETE FROM ai_threads WHERE thread_id = 'thread-1'");
            }

            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM ai_messages")) {
                assertTrue(resultSet.next());
                assertEquals(0, resultSet.getInt(1));
            }
        }
    }
}
