package org.leo.ai.service;

import org.leo.core.config.LeoConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 启动时将 classpath:skills/** 下的内置 skill 文件同步到 VFS/skills/。
 * 开发阶段以 classpath 内置 catalog 为唯一真源，始终覆盖同路径运行副本；
 * 自定义 skill 应使用不同目录名且 manifest.source=custom/imported。
 * 目录结构保持原样：skills/{scope}/{skill-name}/{SKILL.md,manifest.yaml} [+ resources/]
 */
@Component
public class SkillSeedInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SkillSeedInitializer.class);

    private static final String SKILLS_DIR  = "skills";
    private static final String SEED_MARKER = "skills/";
    private static final String SEED_PATTERN = "classpath:skills/**/*";

    @Override
    public void run(String... args) throws Exception {
        Path vfsSkillsRoot = Path.of(LeoConfig.getVfsPath()).resolve(SKILLS_DIR).normalize();

        Resource[] seeds;
        try {
            seeds = new PathMatchingResourcePatternResolver().getResources(SEED_PATTERN);
        } catch (Exception e) {
            log.warn("[SkillSeed] 加载内置 skill 资源失败: {}", e.getMessage());
            return;
        }

        int copied  = 0;
        int updated = 0;
        for (Resource seed : seeds) {
            // isReadable() 为 false 表示这是目录条目，跳过
            if (!seed.isReadable()) {
                continue;
            }
            // 从 URI 中提取 skills/ 之后的相对路径
            String uriStr = seed.getURI().toString().replace('\\', '/');
            int idx = uriStr.lastIndexOf(SEED_MARKER);
            if (idx < 0) {
                continue;
            }
            String relativePath = uriStr.substring(idx + SEED_MARKER.length());
            if (relativePath.isBlank()) {
                continue;
            }

            Path destFile = vfsSkillsRoot.resolve(relativePath).normalize();
            // 路径安全校验，防止路径穿越
            if (!destFile.startsWith(vfsSkillsRoot)) {
                log.warn("[SkillSeed] 非法路径，跳过: {}", relativePath);
                continue;
            }

            Files.createDirectories(destFile.getParent());
            try (InputStream in = seed.getInputStream()) {
                boolean existed = Files.exists(destFile);
                Files.copy(in, destFile, StandardCopyOption.REPLACE_EXISTING);
                if (existed) updated++;
                else copied++;
            } catch (Exception e) {
                log.warn("[SkillSeed] 拷贝失败: {} - {}", relativePath, e.getMessage());
            }
        }
        log.info("[SkillSeed] 内置 skill 同步完成: 新增 {} 个文件, 覆盖 {} 个文件", copied, updated);
    }
}
