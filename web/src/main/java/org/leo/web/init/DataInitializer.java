package org.leo.web.init;

import org.leo.core.entity.Team;
import org.leo.core.entity.User;
import org.leo.core.util.PasswordUtil;
import org.leo.service.team.TeamService;
import org.leo.service.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;

/**
 * 应用启动后执行内置数据初始化。
 *
 * <p>初始化内容：
 * <ol>
 *   <li>内置 admin 用户（密码由环境变量指定，未指定时随机生成并写入仅所有者可读的引导文件）。</li>
 *   <li>内置 system-admin 团队，admin 为队长。</li>
 * </ol>
 *
 * <p>若上述数据已存在，则跳过，不会重复创建或覆盖用户密码。
 * 初始化器会把早期版本的 admin-team/adminteam 口径迁移到 system-admin，
 * 保证内置管理员账户与内置管理员团队只有一套规范边界。
 */
@Component
public class DataInitializer {

    private static final Logger logger = LoggerFactory.getLogger(DataInitializer.class);

    private static final String ADMIN_USER_ID   = "admin";
    private static final String ADMIN_USER_NAME = "admin";
    private static final String LEGACY_ADMIN_PASSWORD = "54ikun";
    private static final int GENERATED_PASSWORD_BYTES = 18;
    private static final String ADMIN_TEAM_ID   = TeamService.ADMIN_TEAM_ID;
    private static final String ADMIN_TEAM_NAME = TeamService.ADMIN_TEAM_NAME;
    private static final String LEGACY_ADMIN_TEAM_ID = "admin-team";
    private static final String LEGACY_ADMINTEAM_ID = "adminteam";

    private final UserService userService;
    private final TeamService teamService;
    private final String configuredAdminPassword;
    private final String configuredPasswordFile;

    public DataInitializer(UserService userService, TeamService teamService,
                           @Value("${leo.admin.initial-password:}") String configuredAdminPassword,
                           @Value("${leo.admin.initial-password-file:.leo/initial-admin-password}")
                           String configuredPasswordFile) {
        this.userService = userService;
        this.teamService = teamService;
        this.configuredAdminPassword = configuredAdminPassword;
        this.configuredPasswordFile = configuredPasswordFile;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        initAdminTeam();
        initAdminUser();
        normalizeAdminUser();
        cleanupLegacyAdminTeams();
    }

    // ── 私有初始化逻辑 ────────────────────────────────────────────────────────────

    /** @return true 表示本次新建了 admin 用户 */
    private boolean initAdminUser() {
        if (userService.getUserByName(ADMIN_USER_NAME) != null) {
            logger.debug("内置 admin 用户已存在，跳过初始化");
            return false;
        }
        User admin = new User();
        admin.setUserId(ADMIN_USER_ID);
        admin.setUserName(ADMIN_USER_NAME);
        String initialPassword = resolveInitialAdminPassword();
        admin.setPassword(PasswordUtil.hash(initialPassword));
        admin.setPrivilege(UserService.PRIVILEGE_ADMIN);
        admin.setTeamId(ADMIN_TEAM_ID);
        admin.setStatus(1);
        admin.setLoginCount(0);
        userService.addUser(admin);
        logger.info("内置 admin 用户初始化完成；请首次登录后立即修改密码");
        return true;
    }

    /** @return true 表示本次新建了 system-admin */
    private boolean initAdminTeam() {
        Team existing = teamService.getTeamById(ADMIN_TEAM_ID);
        if (existing != null) {
            logger.debug("内置 system-admin 团队已存在，跳过初始化");
            return false;
        }
        Team team = new Team();
        team.setTeamId(ADMIN_TEAM_ID);
        team.setTeamName(ADMIN_TEAM_NAME);
        team.setLeaderId(ADMIN_USER_ID);
        team.setDescription("系统内置管理员团队");
        team.setStatus(1);
        teamService.addTeam(team);
        logger.info("内置 system-admin 团队初始化完成");
        return true;
    }

    private void normalizeAdminUser() {
        User admin = userService.getUserById(ADMIN_USER_ID);
        if (admin == null) {
            admin = userService.getUserByName(ADMIN_USER_NAME);
        }
        if (admin == null) {
            return;
        }

        boolean changed = false;
        if (!ADMIN_USER_ID.equals(admin.getUserId())) {
            logger.warn("发现用户名为 admin 但 userId 非 admin 的账户，跳过内置账户迁移: {}", admin.getUserId());
            return;
        }
        if (!ADMIN_USER_NAME.equals(admin.getUserName())) {
            admin.setUserName(ADMIN_USER_NAME);
            changed = true;
        }
        if (!UserService.PRIVILEGE_ADMIN.equals(admin.getPrivilege())) {
            admin.setPrivilege(UserService.PRIVILEGE_ADMIN);
            changed = true;
        }
        if (admin.getStatus() == null || admin.getStatus() != 1) {
            admin.setStatus(1);
            changed = true;
        }
        if (!ADMIN_TEAM_ID.equals(admin.getTeamId())) {
            admin.setTeamId(ADMIN_TEAM_ID);
            changed = true;
        }
        // data.sql and older installations contain the known legacy MD5 of the
        // initial password. Upgrade that deterministic value immediately.
        if (PasswordUtil.needsRehash(admin.getPassword())
                && PasswordUtil.verify(LEGACY_ADMIN_PASSWORD, admin.getPassword())) {
            admin.setPassword(PasswordUtil.hash(LEGACY_ADMIN_PASSWORD));
            changed = true;
        }
        if (changed) {
            userService.updateUser(admin);
            logger.info("内置 admin 用户已归一到 {} 团队", ADMIN_TEAM_ID);
        }
    }

    private String resolveInitialAdminPassword() {
        if (configuredAdminPassword != null && !configuredAdminPassword.isBlank()) {
            logger.info("使用 LEO_ADMIN_INITIAL_PASSWORD 初始化管理员口令");
            return configuredAdminPassword;
        }
        Path passwordFile = Path.of(configuredPasswordFile == null || configuredPasswordFile.isBlank()
                        ? ".leo/initial-admin-password" : configuredPasswordFile)
                .toAbsolutePath().normalize();
        try {
            Path parent = passwordFile.getParent();
            if (parent != null) Files.createDirectories(parent);
            if (!Files.exists(passwordFile)) {
                byte[] random = new byte[GENERATED_PASSWORD_BYTES];
                new SecureRandom().nextBytes(random);
                String generated = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
                try {
                    Files.writeString(passwordFile, generated, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                } catch (FileAlreadyExistsException ignored) {
                    // 并发初始化时由另一个进程创建，下面统一读取。
                }
            }
            tightenFilePermissions(passwordFile);
            String password = Files.readString(passwordFile, StandardCharsets.UTF_8).trim();
            logger.warn("未配置管理员初始密码；已生成随机口令，请从 {} 读取", passwordFile);
            return password;
        } catch (Exception e) {
            throw new IllegalStateException("无法创建管理员初始密码，请设置 LEO_ADMIN_INITIAL_PASSWORD", e);
        }
    }

    private static void tightenFilePermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | java.io.IOException ignored) {
            // 非 POSIX 文件系统依赖宿主访问控制。
        }
    }

    private void cleanupLegacyAdminTeams() {
        cleanupLegacyTeamIfUnused(LEGACY_ADMIN_TEAM_ID);
        cleanupLegacyTeamIfUnused(LEGACY_ADMINTEAM_ID);
    }

    private void cleanupLegacyTeamIfUnused(String teamId) {
        if (ADMIN_TEAM_ID.equals(teamId)) {
            return;
        }
        Team team = teamService.getTeamById(teamId);
        if (team == null) {
            return;
        }
        if (!userService.getUserByTeamId(teamId).isEmpty()) {
            logger.info("旧内置团队 {} 仍有成员，暂不自动删除", teamId);
            return;
        }
        try {
            teamService.delTeam(teamId);
            logger.info("已清理无人使用的旧内置团队 {}", teamId);
        } catch (Exception e) {
            logger.warn("清理旧内置团队 {} 失败: {}", teamId, e.getMessage());
        }
    }
}
