package org.leo.core.init;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

/** Initializes the current release schema and seed data for a fresh installation. */
@Component
@Order(0)
public class DatabaseInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseInitializer.class);
    private static final String PLACEHOLDER_KEY = "placeholder-configure-db-or-env";

    private final DataSource dataSource;

    @Value("${spring.ai.openai.api-key:}")
    private String openaiApiKey;

    public DatabaseInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(String... args) throws Exception {
        enableWalMode();
        rebuildLegacyEventJournal();
        ensureTurnProtocolColumns();
        ensureRunLeaseTokenColumn();
        validateAiConversationSchema();
        validateApiKeys();
        if (needsSeedData()) {
            log.info("检测到全新数据库，写入默认团队与基础配置；管理员账户由安全引导流程创建...");
            executeScript("sql/data.sql");
        }
    }

    private void ensureTurnProtocolColumns() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            Set<String> columns = tableColumns(connection, "ai_turns");
            if (columns.isEmpty()) return;
            try (Statement statement = connection.createStatement()) {
                addColumnIfMissing(statement, columns, "protocol_status",
                        "ALTER TABLE ai_turns ADD COLUMN protocol_status "
                                + "VARCHAR(16) NOT NULL DEFAULT 'completed'");
                addColumnIfMissing(statement, columns, "dispatch_status",
                        "ALTER TABLE ai_turns ADD COLUMN dispatch_status "
                                + "VARCHAR(16) NOT NULL DEFAULT 'completed'");
                addColumnIfMissing(statement, columns, "command_scope",
                        "ALTER TABLE ai_turns ADD COLUMN command_scope VARCHAR(16)");
                addColumnIfMissing(statement, columns, "command_json",
                        "ALTER TABLE ai_turns ADD COLUMN command_json TEXT");
                addColumnIfMissing(statement, columns, "client_user_message_id",
                        "ALTER TABLE ai_turns ADD COLUMN client_user_message_id VARCHAR(128)");
                addColumnIfMissing(statement, columns, "user_item_id",
                        "ALTER TABLE ai_turns ADD COLUMN user_item_id VARCHAR(64)");
                addColumnIfMissing(statement, columns, "assistant_item_id",
                        "ALTER TABLE ai_turns ADD COLUMN assistant_item_id VARCHAR(64)");
                addColumnIfMissing(statement, columns, "started_at",
                        "ALTER TABLE ai_turns ADD COLUMN started_at INTEGER");
                addColumnIfMissing(statement, columns, "interrupt_requested",
                        "ALTER TABLE ai_turns ADD COLUMN interrupt_requested "
                                + "INTEGER NOT NULL DEFAULT 0");
                addColumnIfMissing(statement, columns, "error_message",
                        "ALTER TABLE ai_turns ADD COLUMN error_message TEXT");
                statement.executeUpdate("""
                        CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_turns_client_message
                        ON ai_turns(thread_id, client_user_message_id)
                        WHERE client_user_message_id IS NOT NULL
                        """);
                statement.executeUpdate("DROP INDEX IF EXISTS uk_ai_turns_active_thread");
                statement.executeUpdate("""
                        CREATE UNIQUE INDEX uk_ai_turns_active_thread
                        ON ai_turns(thread_id)
                        WHERE dispatch_status IN ('running', 'cancelling')
                        """);
            }
        }
    }

    private void addColumnIfMissing(Statement statement,
                                    Set<String> columns,
                                    String column,
                                    String sql) throws SQLException {
        if (!columns.contains(column)) {
            statement.executeUpdate(sql);
            columns.add(column);
        }
    }

    private void ensureRunLeaseTokenColumn() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            Set<String> columns = tableColumns(connection, "ai_runs");
            if (columns.isEmpty() || columns.contains("lease_token")) return;
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        "ALTER TABLE ai_runs ADD COLUMN lease_token VARCHAR(64)");
            }
            log.info("已为 ai_runs 增加执行租约 fencing token");
        }
    }

    /**
     * 事件日志属于可重建的运行态数据。旧版本 ai_events 缺少稳定路由字段时只重建
     * 该表，不影响线程、消息、模型配置及其他业务数据。
     */
    private void rebuildLegacyEventJournal() throws SQLException {
        Set<String> required = Set.of(
                "event_id", "thread_id", "run_id", "turn_id", "item_id",
                "subagent_invocation_id", "event_seq", "timestamp", "name", "data_json");
        try (Connection connection = dataSource.getConnection()) {
            Set<String> actual = tableColumns(connection, "ai_events");
            if (actual.isEmpty() || actual.containsAll(required)) return;
            log.info("重建旧版 AI 事件日志以启用持久化 Turn/Item 游标");
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("DROP TABLE ai_events");
            }
        }
        executeScript("sql/schema.sql");
    }

    private void executeScript(String resource) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(resource));
        }
    }

    private boolean needsSeedData() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM users")) {
            return !result.next() || result.getLong(1) == 0;
        } catch (SQLException error) {
            throw new IllegalStateException("检查数据库初始化状态失败", error);
        }
    }

    private void validateApiKeys() {
        if (isPlaceholderOrBlank(openaiApiKey)) {
            log.warn("OpenAI API key 未配置（环境变量 OPENAI_API_KEY 或数据库 AI 渠道）");
            log.warn("如已通过数据库 AI 渠道配置，可忽略以上警告");
        }
    }

    private boolean isPlaceholderOrBlank(String key) {
        return key == null || key.isBlank() || key.contains(PLACEHOLDER_KEY);
    }

    private void enableWalMode() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA journal_mode=WAL")) {
            if (result.next()) {
                log.info("SQLite journal_mode: {}", result.getString(1));
            }
        } catch (SQLException error) {
            log.warn("开启 WAL 模式失败: {}", error.getMessage());
        }
    }

    /**
     * AI 对话结构采用全新 Turn 模型，不对旧表做隐式兼容或半迁移。
     * 发现旧结构时直接失败，避免应用运行到首轮消息写入时才产生难以理解的 SQL 异常。
     */
    private void validateAiConversationSchema() {
        try (Connection connection = dataSource.getConnection()) {
            requireColumns(connection, "ai_turns",
                    Set.of("turn_id", "thread_id", "status", "created_at", "completed_at",
                            "protocol_status", "dispatch_status", "command_scope",
                            "command_json", "client_user_message_id",
                            "user_item_id", "assistant_item_id", "started_at",
                            "interrupt_requested", "error_message"));
            requireColumns(connection, "ai_runs",
                    Set.of("run_id", "thread_id", "turn_id", "status",
                            "error_category", "raw_error_message",
                            "trace_id", "trace_json", "lease_token"));
            requireColumns(connection, "ai_messages",
                    Set.of("message_id", "thread_id", "turn_id", "run_id",
                            "message_seq", "status", "role"));
            requireColumns(connection, "ai_events",
                    Set.of("event_id", "thread_id", "run_id", "turn_id", "item_id",
                            "subagent_invocation_id", "event_seq", "timestamp",
                            "name", "data_json"));
            requireColumns(connection, "ai_thread_leases",
                    Set.of("thread_id", "owner_id", "lease_token", "acquired_at",
                            "heartbeat_at", "expires_at"));
        } catch (SQLException error) {
            throw new IllegalStateException("校验 AI 对话数据库结构失败", error);
        }
    }

    private void requireColumns(Connection connection, String table,
                                Set<String> requiredColumns) throws SQLException {
        Set<String> actual = tableColumns(connection, table);
        if (!actual.containsAll(requiredColumns)) {
            Set<String> missing = new HashSet<>(requiredColumns);
            missing.removeAll(actual);
            throw new IllegalStateException(
                    "AI 数据库结构已过期，缺少 " + table + "." + missing
                            + "；当前版本不兼容旧 AI 对话数据，请重建数据库");
        }
    }

    private Set<String> tableColumns(Connection connection, String table)
            throws SQLException {
        Set<String> actual = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA table_info('" + table + "')")) {
            while (result.next()) {
                actual.add(result.getString("name"));
            }
        }
        return actual;
    }

}
