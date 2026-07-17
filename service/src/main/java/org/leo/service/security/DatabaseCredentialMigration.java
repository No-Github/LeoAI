package org.leo.service.security;

import org.leo.core.entity.PuppetDatabaseConnection;
import org.leo.dao.mapper.PuppetDatabaseConnectionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Encrypts historical plaintext database profile passwords after schema migration. */
@Component
@Order(20)
public class DatabaseCredentialMigration implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseCredentialMigration.class);

    private final PuppetDatabaseConnectionMapper mapper;
    private final DatabaseCredentialCryptoService crypto;

    public DatabaseCredentialMigration(PuppetDatabaseConnectionMapper mapper,
                                       DatabaseCredentialCryptoService crypto) {
        this.mapper = mapper;
        this.crypto = crypto;
    }

    @Override
    public void run(String... args) {
        int migrated = 0;
        for (PuppetDatabaseConnection connection : mapper.selectAll()) {
            String password = connection.getPassword();
            if (password == null || password.isBlank() || crypto.isEncrypted(password)) continue;
            mapper.updatePassword(connection.getConnectionId(), crypto.encrypt(password));
            migrated++;
        }
        if (migrated > 0) log.info("已加密迁移 {} 条数据库连接密码", migrated);
    }
}
