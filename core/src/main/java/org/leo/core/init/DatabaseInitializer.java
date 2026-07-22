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
        validateApiKeys();
        if (needsSeedData()) {
            log.info("检测到全新数据库，写入默认团队与基础配置；管理员账户由安全引导流程创建...");
            executeScript("sql/data.sql");
        }
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
}
