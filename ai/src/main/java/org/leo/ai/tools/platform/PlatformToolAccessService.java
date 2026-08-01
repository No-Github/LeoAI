package org.leo.ai.tools.platform;

import org.leo.ai.agent.AiToolContext;
import org.leo.core.entity.AiExecutionPolicy;
import org.leo.core.entity.Puppet;
import org.leo.core.entity.User;
import org.leo.core.security.AccessPolicy;
import org.leo.service.user.UserService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 平台 Agent 工具对用户身份和 Puppet 资源范围的二次校验。 */
@Component
public class PlatformToolAccessService {

    private final UserService userService;

    public PlatformToolAccessService(UserService userService) {
        this.userService = userService;
    }

    public User requireCurrentUser() {
        AiExecutionPolicy policy = AiToolContext.requireExecutionPolicy();
        User user = userService.getUserById(policy.getUserId());
        if (user == null || !Integer.valueOf(1).equals(user.getStatus())) {
            throw new SecurityException("当前用户不存在或已禁用");
        }
        return user;
    }

    public List<Puppet> filterVisible(List<Puppet> puppets) {
        User user = requireCurrentUser();
        List<Puppet> visible = new ArrayList<>();
        if (puppets == null) return visible;
        for (Puppet puppet : puppets) {
            if (canView(puppet, user)) visible.add(puppet);
        }
        return visible;
    }

    public Puppet requireVisible(Puppet puppet) {
        if (puppet == null) throw new IllegalArgumentException("Puppet不存在");
        if (!canView(puppet, requireCurrentUser())) {
            throw new SecurityException("无权限访问此 Puppet");
        }
        return puppet;
    }

    public Puppet requireModifiable(Puppet puppet) {
        if (puppet == null) throw new IllegalArgumentException("Puppet不存在");
        if (!canModify(puppet, requireCurrentUser())) {
            throw new SecurityException("无权限修改此 Puppet");
        }
        return puppet;
    }

    public boolean canView(Puppet puppet, User user) {
        if (AccessPolicy.canAccessPuppet(puppet, user)) return true;
        return hasLegacyTeamOwnership(puppet, user);
    }

    public boolean canModify(Puppet puppet, User user) {
        if (AccessPolicy.canModifyPuppet(puppet, user)) return true;
        return AccessPolicy.isLeader(user)
                && AccessPolicy.isTeamVisiblePermission(
                        puppet != null ? puppet.getPermission() : null)
                && hasLegacyTeamOwnership(puppet, user);
    }

    public String normalizePermission(String permission) {
        if (AccessPolicy.PERMISSION_PUBLIC.equals(permission)) {
            return AccessPolicy.PERMISSION_PUBLIC;
        }
        if (AccessPolicy.PERMISSION_TEAM.equals(permission)) {
            return AccessPolicy.PERMISSION_TEAM;
        }
        return AccessPolicy.PERMISSION_PRIVATE;
    }

    private boolean hasLegacyTeamOwnership(Puppet puppet, User user) {
        if (puppet == null || user == null
                || user.getTeamId() == null || user.getTeamId().isBlank()
                || !AccessPolicy.isTeamVisiblePermission(puppet.getPermission())
                || (puppet.getTeamId() != null && !puppet.getTeamId().isBlank())) {
            return false;
        }
        User owner = userService.getUserById(puppet.getCreateByUserId());
        return owner != null && user.getTeamId().equals(owner.getTeamId());
    }
}
