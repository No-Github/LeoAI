package org.leo.ai.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 数据库初始化后，把历史明文模型凭据迁移为 AES-GCM 密文。 */
@Component
@Order(100)
public class AiSecretMigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AiSecretMigrationRunner.class);
    private final AiModelConfigService configService;

    public AiSecretMigrationRunner(AiModelConfigService configService) {
        this.configService = configService;
    }

    @Override
    public void run(ApplicationArguments args) {
        int migrated = configService.migrateSecretsAtRest();
        if (migrated > 0) {
            log.info("已加密迁移 {} 条 AI Provider/Model Secret 记录", migrated);
        }
    }
}
