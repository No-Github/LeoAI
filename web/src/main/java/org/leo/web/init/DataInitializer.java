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
 *   <li>内置 admin 用户（初始密码固定为 54ikun）。</li>
 *   <li>内置 system-admin 团队，admin 为队长。</li>
 * </ol>
 *
 * <p>若上述数据已存在，则跳过，不会重复创建或覆盖用户密码。
 */
@Component
public class DataInitializer {

    private static final Logger logger = LoggerFactory.getLogger(DataInitializer.class);

    private static final String ADMIN_USER_ID   = "admin";
    private static final String ADMIN_USER_NAME = "admin";
    private static final String INITIAL_ADMIN_PASSWORD = "54ikun";
    private static final String ADMIN_TEAM_ID   = TeamService.ADMIN_TEAM_ID;
    private static final String ADMIN_TEAM_NAME = TeamService.ADMIN_TEAM_NAME;

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
        admin.setPassword(PasswordUtil.hash(INITIAL_ADMIN_PASSWORD));
        admin.setPrivilege(UserService.PRIVILEGE_ADMIN);
        admin.setTeamId(ADMIN_TEAM_ID);
        admin.setStatus(1);
        admin.setLoginCount(0);
        admin.setPasswordChangeRequired(1);
        userService.addUser(admin);
        logger.info("内置 admin 用户初始化完成；首次登录将强制修改密码");
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

}
