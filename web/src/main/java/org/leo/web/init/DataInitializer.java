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
import org.springframework.stereotype.Component;

/**
 * 应用启动后执行内置数据初始化。
 *
 * <p>初始化内容：
 * <ol>
 *   <li>内置 admin 用户（用户名: admin，默认密码: 54ikun，以 MD5 存储）。</li>
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
    private static final String ADMIN_PASSWORD  = "54ikun";   // 明文，存储时自动 MD5
    private static final String ADMIN_TEAM_ID   = TeamService.ADMIN_TEAM_ID;
    private static final String ADMIN_TEAM_NAME = TeamService.ADMIN_TEAM_NAME;
    private static final String LEGACY_ADMIN_TEAM_ID = "admin-team";
    private static final String LEGACY_ADMINTEAM_ID = "adminteam";

    private final UserService userService;
    private final TeamService teamService;

    public DataInitializer(UserService userService, TeamService teamService) {
        this.userService = userService;
        this.teamService = teamService;
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
        admin.setPassword(PasswordUtil.md5(ADMIN_PASSWORD));
        admin.setPrivilege(UserService.PRIVILEGE_ADMIN);
        admin.setTeamId(ADMIN_TEAM_ID);
        admin.setStatus(1);
        admin.setLoginCount(0);
        userService.addUser(admin);
        logger.info("内置 admin 用户初始化完成");
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
        if (changed) {
            userService.updateUser(admin);
            logger.info("内置 admin 用户已归一到 {} 团队", ADMIN_TEAM_ID);
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
