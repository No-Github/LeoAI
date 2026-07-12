package org.leo.service.security;

import org.leo.core.entity.PuppetJdbc;
import org.leo.dao.mapper.PuppetJdbcMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 启动时将历史明文 JDBC 密码原位迁移为 AES-GCM 密文。 */
@Component
@Order(20)
public class JdbcCredentialMigration implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(JdbcCredentialMigration.class);

    private final PuppetJdbcMapper mapper;
    private final JdbcCredentialCryptoService crypto;

    public JdbcCredentialMigration(PuppetJdbcMapper mapper, JdbcCredentialCryptoService crypto) {
        this.mapper = mapper;
        this.crypto = crypto;
    }

    @Override
    public void run(String... args) {
        int migrated = 0;
        for (PuppetJdbc connection : mapper.selectAll()) {
            String password = connection.getPassword();
            if (password == null || password.isBlank() || crypto.isEncrypted(password)) continue;
            mapper.updatePassword(connection.getConnId(), crypto.encrypt(password));
            migrated++;
        }
        if (migrated > 0) log.info("已加密迁移 {} 条 JDBC 连接密码", migrated);
    }
}
