package org.leo.ai.tools.platform;

import org.leo.core.entity.Puppet;
import org.leo.core.entity.User;
import org.leo.core.security.AccessPolicy;
import org.leo.service.PuppetConnService;
import org.leo.service.PuppetService;
import org.leo.service.disguise.DisguiseService;
import org.leo.service.team.TeamService;
import org.leo.service.user.UserService;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Component("platformPuppetTools")
public class PuppetTools {
    private final PuppetService puppetService;
    private final PuppetConnService puppetConnService;
    private final UserService userService;
    private final TeamService teamService;
    private final DisguiseService disguiseService;
    private final PlatformToolAccessService accessService;

    public PuppetTools(PuppetService puppetService, PuppetConnService puppetConnService,
                       UserService userService, TeamService teamService,
                       DisguiseService disguiseService,
                       PlatformToolAccessService accessService) {
        this.puppetService = puppetService;
        this.puppetConnService = puppetConnService;
        this.userService = userService;
        this.teamService = teamService;
        this.disguiseService = disguiseService;
        this.accessService = accessService;
    }

    @Tool("测试指定 Puppet 的连通性，不创建会话。返回 success、hostId、components 和 latencyMs；失败时返回 message。")
    public Map<String, Object> testPuppetConnection(String puppetId) {
        String id = requireNonBlank(puppetId, "puppetId不能为空");
        accessService.requireVisible(puppetService.findPuppetById(id));
        return puppetConnService.testConnection(id);
    }

    @Tool("获取当前平台所有 Puppet。")
    public List<Puppet> getAllPuppet() {
        return accessService.filterVisible(puppetService.getAllPuppet());
    }

    @Tool("根据 puppetId 获取 Puppet 详情。")
    public Puppet getPuppetById(String puppetId) {
        return accessService.requireVisible(puppetService.findPuppetById(
                requireNonBlank(puppetId, "puppetId不能为空")));
    }

    @Tool("根据创建人 userId 获取 Puppet 列表。")
    public List<Puppet> getPuppetsByCreateUserId(String createUserId) {
        return accessService.filterVisible(puppetService.findPuppetByCreateUserId(
                requireNonBlank(createUserId, "createUserId不能为空")));
    }

    @Tool("根据 parentPuppetId 获取子 Puppet 列表。")
    public List<Puppet> getPuppetsByParentPuppetId(String parentPuppetId) {
        return accessService.filterVisible(puppetService.findPuppetByParentPuppetId(
                requireNonBlank(parentPuppetId, "parentPuppetId不能为空")));
    }

    @Tool("根据权限获取 Puppet 列表，例如 read 或 write。")
    public List<Puppet> getPuppetsByPermission(String permission) {
        return accessService.filterVisible(puppetService.findPuppetByPermission(
                requireNonBlank(permission, "permission不能为空")));
    }

    @Tool("创建平台 Puppet。puppetName、connLink 必填；创建人和团队范围由当前用户身份强制约束，未传 puppetId 会自动生成。urlStrategy、paddingStrategy、headerNoiseStrategy、tlsFingerprintStrategy、componentClassNameStrategy 均为 JSON 高级配置。")
    public Map<String, Object> addPuppet(String puppetName, String createByUserId, String connLink,
                                         String teamId, String parentPuppetId, String protocol,
                                         String headers, String reqDisguiseId, String respDisguiseId,
                                         Integer proxyEnabled, String proxyType, String proxyHost, Integer proxyPort,
                                         Integer balanceEnabled, Integer maxReqCount, String permission,
                                         String lastHeartbeat, Integer heartbeatInterval,
                                         String remark, String puppetId, String urlStrategy,
                                         String paddingStrategy, String headerNoiseStrategy,
                                         String tlsFingerprintStrategy, String componentClassNameStrategy) {
        User caller = accessService.requireCurrentUser();
        boolean admin = AccessPolicy.isAdmin(caller);
        String requestedOwner = trimToNull(createByUserId);
        if (!admin && requestedOwner != null
                && !caller.getUserId().equals(requestedOwner)) {
            throw new SecurityException("不能以其他用户身份创建 Puppet");
        }
        String effectivePermission = accessService.normalizePermission(permission);
        if (AccessPolicy.PERMISSION_PUBLIC.equals(effectivePermission) && !admin) {
            throw new SecurityException("只有管理员可以创建公开 Puppet");
        }
        String effectiveTeamId = resolveTeamId(caller, admin, teamId, null);
        String effectiveParentId = trimToNull(parentPuppetId);
        if (effectiveParentId != null && !"root".equals(effectiveParentId)) {
            accessService.requireVisible(puppetService.findPuppetById(effectiveParentId));
        }

        Puppet puppet = new Puppet();
        puppet.setPuppetId(defaultIfBlank(puppetId, UUID.randomUUID().toString()));
        puppet.setPuppetName(requireNonBlank(puppetName, "puppetName不能为空"));
        puppet.setCreateByUserId(admin && requestedOwner != null
                ? requestedOwner : caller.getUserId());
        puppet.setConnLink(requireNonBlank(connLink, "connLink不能为空"));
        puppet.setTeamId(effectiveTeamId);
        puppet.setParentPuppetId(effectiveParentId);
        puppet.setProtocol(defaultIfBlank(protocol, puppet.getProtocol()));
        puppet.setHeaders(trimToNull(headers));
        puppet.setReqDisguiseId(trimToNull(reqDisguiseId));
        puppet.setRespDisguiseId(trimToNull(respDisguiseId));
        if (proxyEnabled != null) {
            puppet.setProxyEnabled(proxyEnabled);
        }
        if (proxyType != null) {
            puppet.setProxyType(trimToNull(proxyType));
        }
        if (proxyHost != null) {
            puppet.setProxyHost(trimToNull(proxyHost));
        }
        if (proxyPort != null) {
            puppet.setProxyPort(proxyPort);
        }
        if (balanceEnabled != null) {
            puppet.setBalanceEnabled(balanceEnabled);
        }
        if (maxReqCount != null) {
            puppet.setMaxReqCount(maxReqCount);
        }
        puppet.setPermission(effectivePermission);
        if (lastHeartbeat != null) {
            puppet.setLastHeartbeat(trimToNull(lastHeartbeat));
        }
        if (heartbeatInterval != null) {
            puppet.setHeartbeatInterval(heartbeatInterval);
        }
        puppet.setRemark(trimToNull(remark));
        puppet.setUrlStrategy(trimToNull(urlStrategy));
        puppet.setPaddingStrategy(trimToNull(paddingStrategy));
        puppet.setHeaderNoiseStrategy(trimToNull(headerNoiseStrategy));
        puppet.setTlsFingerprintStrategy(trimToNull(tlsFingerprintStrategy));
        puppet.setComponentClassNameStrategy(trimToNull(componentClassNameStrategy));

        validatePuppetRelations(puppet);
        boolean created = puppetService.insertPuppet(puppet);
        return buildResult("created", created, puppet.getPuppetId(), puppet.getPuppetName());
    }

    @Tool("更新平台 Puppet。puppetId 必填，其余字段按需更新。urlStrategy、paddingStrategy、headerNoiseStrategy、tlsFingerprintStrategy、componentClassNameStrategy 均为 JSON 高级配置。")
    public Map<String, Object> updatePuppet(String puppetId, String puppetName, String createByUserId,
                                            String connLink, String teamId, String parentPuppetId,
                                            String protocol, String headers, String reqDisguiseId,
                                            String respDisguiseId, Integer proxyEnabled, String proxyType,
                                            String proxyHost, Integer proxyPort, Integer balanceEnabled,
                                            Integer maxReqCount, String permission, String lastHeartbeat,
                                            Integer heartbeatInterval, String remark, String urlStrategy,
                                            String paddingStrategy, String headerNoiseStrategy,
                                            String tlsFingerprintStrategy, String componentClassNameStrategy) {
        Puppet existing = puppetService.findPuppetById(requireNonBlank(puppetId, "puppetId不能为空"));
        accessService.requireModifiable(existing);
        User caller = accessService.requireCurrentUser();
        boolean admin = AccessPolicy.isAdmin(caller);

        if (!isBlank(puppetName)) {
            existing.setPuppetName(puppetName.trim());
        }
        if (!isBlank(createByUserId)) {
            if (!admin && !Objects.equals(
                    existing.getCreateByUserId(), createByUserId.trim())) {
                throw new SecurityException("不能变更 Puppet 创建人");
            }
            if (admin) existing.setCreateByUserId(createByUserId.trim());
        }
        if (!isBlank(connLink)) {
            existing.setConnLink(connLink.trim());
        }
        if (teamId != null) {
            existing.setTeamId(resolveTeamId(caller, admin, teamId, existing));
        }
        if (parentPuppetId != null) {
            String effectiveParentId = trimToNull(parentPuppetId);
            if (effectiveParentId != null && !"root".equals(effectiveParentId)) {
                accessService.requireVisible(puppetService.findPuppetById(effectiveParentId));
            }
            existing.setParentPuppetId(effectiveParentId);
        }
        if (!isBlank(protocol)) {
            existing.setProtocol(protocol.trim());
        }
        if (headers != null) {
            existing.setHeaders(trimToNull(headers));
        }
        if (reqDisguiseId != null) {
            existing.setReqDisguiseId(trimToNull(reqDisguiseId));
        }
        if (respDisguiseId != null) {
            existing.setRespDisguiseId(trimToNull(respDisguiseId));
        }
        if (proxyEnabled != null) {
            existing.setProxyEnabled(proxyEnabled);
        }
        if (proxyType != null) {
            existing.setProxyType(trimToNull(proxyType));
        }
        if (proxyHost != null) {
            existing.setProxyHost(trimToNull(proxyHost));
        }
        if (proxyPort != null) {
            existing.setProxyPort(proxyPort);
        }
        if (balanceEnabled != null) {
            existing.setBalanceEnabled(balanceEnabled);
        }
        if (maxReqCount != null) {
            existing.setMaxReqCount(maxReqCount);
        }
        if (permission != null) {
            String effectivePermission = accessService.normalizePermission(permission);
            if (AccessPolicy.PERMISSION_PUBLIC.equals(effectivePermission) && !admin) {
                throw new SecurityException("只有管理员可以设置公开 Puppet");
            }
            existing.setPermission(effectivePermission);
        }
        if (lastHeartbeat != null) {
            existing.setLastHeartbeat(trimToNull(lastHeartbeat));
        }
        if (heartbeatInterval != null) {
            existing.setHeartbeatInterval(heartbeatInterval);
        }
        if (remark != null) {
            existing.setRemark(trimToNull(remark));
        }
        if (urlStrategy != null) {
            existing.setUrlStrategy(trimToNull(urlStrategy));
        }
        if (paddingStrategy != null) {
            existing.setPaddingStrategy(trimToNull(paddingStrategy));
        }
        if (headerNoiseStrategy != null) {
            existing.setHeaderNoiseStrategy(trimToNull(headerNoiseStrategy));
        }
        if (tlsFingerprintStrategy != null) {
            existing.setTlsFingerprintStrategy(trimToNull(tlsFingerprintStrategy));
        }
        if (componentClassNameStrategy != null) {
            existing.setComponentClassNameStrategy(trimToNull(componentClassNameStrategy));
        }

        validatePuppetRelations(existing);
        boolean updated = puppetService.updatePuppetById(existing);
        return buildResult("updated", updated, existing.getPuppetId(), existing.getPuppetName());
    }

    @Tool("删除指定 Puppet。")
    public Map<String, Object> deletePuppet(String puppetId) {
        Puppet puppet = puppetService.findPuppetById(requireNonBlank(puppetId, "puppetId不能为空"));
        accessService.requireModifiable(puppet);
        boolean deleted = puppetService.deletePuppetById(puppet.getPuppetId());
        return buildResult("deleted", deleted, puppet.getPuppetId(), puppet.getPuppetName());
    }

    private void validatePuppetRelations(Puppet puppet) {
        if (userService.getUserById(puppet.getCreateByUserId()) == null) {
            throw new IllegalArgumentException("创建用户不存在");
        }
        if (!isBlank(puppet.getTeamId()) && teamService.getTeamById(puppet.getTeamId()) == null) {
            throw new IllegalArgumentException("团队不存在");
        }
        if (!isBlank(puppet.getParentPuppetId())
                && puppetService.findPuppetById(puppet.getParentPuppetId()) == null) {
            throw new IllegalArgumentException("父 Puppet 不存在");
        }
        if (!isBlank(puppet.getReqDisguiseId())) {
            disguiseService.getDisguiseById(puppet.getReqDisguiseId());
        }
        if (!isBlank(puppet.getRespDisguiseId())) {
            disguiseService.getDisguiseById(puppet.getRespDisguiseId());
        }
    }

    private String resolveTeamId(User caller, boolean admin,
                                 String requestedTeamId, Puppet existing) {
        String requested = trimToNull(requestedTeamId);
        if (admin) {
            return requested != null
                    ? requested
                    : existing != null ? existing.getTeamId() : caller.getTeamId();
        }
        String callerTeamId = trimToNull(caller.getTeamId());
        if (requested != null && !requested.equals(callerTeamId)) {
            throw new SecurityException("不能把 Puppet 分配到其他团队");
        }
        return callerTeamId != null
                ? callerTeamId
                : existing != null ? existing.getTeamId() : null;
    }

    private Map<String, Object> buildResult(String status, boolean success, String puppetId, String puppetName) {
        HashMap<String, Object> result = new HashMap<>();
        result.put("status", status);
        result.put("success", success);
        result.put("puppetId", puppetId);
        result.put("puppetName", puppetName);
        return result;
    }

    private String requireNonBlank(String value, String message) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            throw new IllegalArgumentException(message);
        }
        return trimmed;
    }

    private String defaultIfBlank(String value, String defaultValue) {
        String trimmed = trimToNull(value);
        return trimmed == null ? defaultValue : trimmed;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean isBlank(String value) {
        return trimToNull(value) == null;
    }
}
