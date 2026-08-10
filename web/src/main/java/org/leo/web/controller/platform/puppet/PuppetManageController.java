package org.leo.web.controller.platform.puppet;

import jakarta.servlet.http.HttpServletRequest;
import org.leo.core.entity.Puppet;
import org.leo.core.entity.Project;
import org.leo.core.entity.User;
import org.leo.service.PuppetService;
import org.leo.service.UrlProbeService;
import org.leo.service.project.ProjectService;
import org.leo.service.user.UserService;
import org.leo.core.util.ApiResponse;
import org.leo.core.util.json.JsonUtil;
import org.leo.core.util.request.ComponentClassNameStrategy;
import org.leo.web.util.ControllerUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Puppet管理控制器
 */
@RestController
@RequestMapping("/platform/puppet-manage")
public class PuppetManageController {

    private static final Logger logger = LoggerFactory.getLogger(PuppetManageController.class);

    // 参数名常量
    private static final String PARAM_PARENT_PUPPET_ID = "parentPuppetId";
    private static final String PARAM_PUPPET_ID_LOWER = "puppetId";
    
    // 会话属性常量
    private static final String SESSION_ATTR_USER = "user";
    
    // 权限常量
    private static final String PRIVILEGE_ADMIN = "admin";
    private static final String PRIVILEGE_LEADER = "leader";
    private static final String PERMISSION_PRIVATE = "private";
    private static final String PERMISSION_TEAM = "team";
    private static final String PERMISSION_PUBLIC = "public";
    
    // 根节点ID
    private static final String ROOT_PARENT_ID = "root";
    
    private final PuppetService puppetService;
    private final UserService userService;
    private final UrlProbeService urlProbeService;
    private final ProjectService projectService;

    public PuppetManageController(PuppetService puppetService, UserService userService,
                                  UrlProbeService urlProbeService,
                                  ProjectService projectService){
        this.puppetService = puppetService;
        this.userService = userService;
        this.urlProbeService = urlProbeService;
        this.projectService = projectService;
    }
    
    @RequestMapping(value = "/children", method = RequestMethod.POST)
    public HashMap<String, Object> getChildrenByParentPuppetId(HttpServletRequest request, @RequestBody HashMap<String, Object> params) {
        try {
            User user = getUserFromSession(request);
            if (user == null || user.getUserId() == null) {
                return ApiResponse.unauthorized("用户未登录");
            }
            String puppetId = ControllerUtil.getRequiredStringParam(params, PARAM_PARENT_PUPPET_ID);
            List<Puppet> puppetList = puppetService.findPuppetByParentPuppetId(puppetId);
            return ApiResponse.success(filterVisiblePuppets(puppetList, user));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @RequestMapping(value = "/puppets", method = RequestMethod.GET)
    public HashMap<String, Object> getPuppet(HttpServletRequest request) {
        User user = getUserFromSession(request);
        if (user == null || user.getUserId() == null) {
            return ApiResponse.unauthorized("用户未登录");
        }
        List<Puppet> mergedList = filterVisiblePuppets(puppetService.getAllPuppet(), user);
        Iterator<Puppet> iterator = mergedList.iterator();
        while (iterator.hasNext()) {
            Puppet puppet = iterator.next();
            String parentPuppetId = puppet.getParentPuppetId();
            if (parentPuppetId == null || !ROOT_PARENT_ID.equals(parentPuppetId)) {
                iterator.remove();
            }
        }
        return ApiResponse.success(mergedList);
    }
    
    @RequestMapping(value = "/puppets", method = RequestMethod.POST)
    @Transactional
    public HashMap<String, Object> addPuppet(
            HttpServletRequest request,
            @RequestBody Puppet puppet,
            @RequestParam(value = "projectId", required = false) String projectId) {
        if (puppet == null) {
            return ApiResponse.badRequest("puppet参数不能为空");
        }
        User user = getUserFromSession(request);
        if (user == null || user.getUserId() == null) {
            return ApiResponse.unauthorized("用户未登录");
        }
        Project project = null;
        if (projectId != null && !projectId.isBlank()) {
            project = projectService.findById(projectId.trim());
            if (project == null) return ApiResponse.notFound("项目不存在");
            if (!projectService.canView(project, user)) return ApiResponse.forbidden("无权限访问此项目");
            if (!projectService.canEditContent(project, user)) {
                return ApiResponse.forbidden("无权限维护此项目的主机归属");
            }
            if (Project.STATUS_ARCHIVED.equals(project.getStatus())) {
                return ApiResponse.badRequest("已归档项目不能添加主机");
            }
        }
        try {
            validateComponentClassNameStrategy(puppet.getComponentClassNameStrategy());
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
        puppet.setCreateByUserId(user.getUserId());
        String permission = normalizePuppetPermission(puppet.getPermission());
        if (PERMISSION_PUBLIC.equals(permission) && !isAdmin(user)) {
            return ApiResponse.forbidden("只有管理员可以创建公开Puppet");
        }
        puppet.setPermission(permission);
        puppet.setTeamId(resolvePuppetTeamId(puppet, user, null));
        String id = UUID.randomUUID().toString();
        puppet.setPuppetId(id);
        boolean result = puppetService.insertPuppet(puppet);
        if (result) {
            if (project != null) {
                projectService.attachPuppets(project.getProjectId(), List.of(id),
                        null, null, null, user.getUserId());
            }
            return ApiResponse.success(Collections.singletonMap("puppetId", id));
        } else {
            return ApiResponse.error("添加Puppet失败");
        }
    }

    /**
     * 导出指定 Puppet 及其完整祖先链。返回顺序始终为祖先在前、子节点在后，
     * 便于客户端在导入时按依赖顺序重建父子关系。
     */
    @RequestMapping(value = "/puppets/export", method = RequestMethod.POST)
    public HashMap<String, Object> exportPuppets(HttpServletRequest request,
                                                  @RequestBody HashMap<String, Object> params) {
        User user = getUserFromSession(request);
        if (user == null || user.getUserId() == null) {
            return ApiResponse.unauthorized("用户未登录");
        }
        Object rawIds = params != null ? params.get("puppetIds") : null;
        if (!(rawIds instanceof Collection<?> values) || values.isEmpty()) {
            return ApiResponse.badRequest("puppetIds不能为空");
        }
        if (values.size() > 200) {
            return ApiResponse.badRequest("单次最多导出200台主机");
        }

        LinkedHashMap<String, Puppet> ordered = new LinkedHashMap<>();
        List<String> requestedIds = new ArrayList<>();
        for (Object value : values) {
            String puppetId = value != null ? value.toString().trim() : "";
            if (puppetId.isEmpty()) {
                continue;
            }
            requestedIds.add(puppetId);
            List<Puppet> chain = new ArrayList<>();
            Set<String> visited = new HashSet<>();
            Puppet current = puppetService.findPuppetById(puppetId);
            if (current == null) {
                return ApiResponse.notFound("Puppet不存在: " + puppetId);
            }
            while (current != null) {
                if (!visited.add(current.getPuppetId())) {
                    return ApiResponse.conflict("Puppet层级存在循环依赖");
                }
                if (!canViewPuppet(current, user)) {
                    return ApiResponse.forbidden("无权限导出主机或其依赖");
                }
                chain.add(current);
                String parentId = current.getParentPuppetId();
                if (parentId == null || parentId.isBlank() || ROOT_PARENT_ID.equals(parentId)) {
                    break;
                }
                current = puppetService.findPuppetById(parentId);
                if (current == null) {
                    return ApiResponse.conflict("Puppet缺少父主机依赖: " + parentId);
                }
            }
            Collections.reverse(chain);
            for (Puppet puppet : chain) {
                ordered.putIfAbsent(puppet.getPuppetId(), puppet);
            }
        }

        if (Boolean.TRUE.equals(params.get("includeDescendants"))) {
            Deque<String> queue = new ArrayDeque<>(requestedIds);
            Set<String> expanded = new HashSet<>();
            while (!queue.isEmpty()) {
                String parentId = queue.removeFirst();
                if (!expanded.add(parentId)) {
                    continue;
                }
                for (Puppet child : puppetService.findPuppetByParentPuppetId(parentId)) {
                    if (!canViewPuppet(child, user)) {
                        continue;
                    }
                    ordered.putIfAbsent(child.getPuppetId(), child);
                    queue.addLast(child.getPuppetId());
                    if (ordered.size() > 1000) {
                        return ApiResponse.badRequest("单次分享最多包含1000台主机");
                    }
                }
            }
        }
        return ApiResponse.success(new ArrayList<>(ordered.values()));
    }
    
    @RequestMapping(value = "/puppets/update", method = RequestMethod.POST)
    public HashMap<String, Object> updatePuppet(HttpServletRequest request, @RequestBody Puppet puppet) {
        if (puppet == null || puppet.getPuppetId() == null || puppet.getPuppetId().isBlank()) {
            return ApiResponse.badRequest("puppet参数或puppetId不能为空");
        }
        User user = getUserFromSession(request);
        if (user == null || user.getUserId() == null) {
            return ApiResponse.unauthorized("用户未登录");
        }
        try {
            validateComponentClassNameStrategy(puppet.getComponentClassNameStrategy());
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
        
        Puppet existingPuppet = puppetService.findPuppetById(puppet.getPuppetId());
        if (existingPuppet == null) {
            return ApiResponse.notFound("Puppet不存在");
        }
        
        // 检查权限：只有创建者或管理员可以更新
        if (!hasPermissionToModify(existingPuppet, user)) {
            return ApiResponse.forbidden("无权限修改此Puppet");
        }

        String permission = normalizePuppetPermission(puppet.getPermission());
        if (PERMISSION_PUBLIC.equals(permission) && !isAdmin(user)) {
            return ApiResponse.forbidden("只有管理员可以设置公开Puppet");
        }
        puppet.setPermission(permission);
        puppet.setCreateByUserId(existingPuppet.getCreateByUserId());
        puppet.setTeamId(resolvePuppetTeamId(puppet, user, existingPuppet));
        
        boolean result = puppetService.updatePuppetById(puppet);
        if (result) {
            return ApiResponse.success();
        } else {
            return ApiResponse.error("更新Puppet失败");
        }
    }

    @RequestMapping(value = "/component-class-name/preview", method = RequestMethod.POST)
    public HashMap<String, Object> previewComponentClassNames(HttpServletRequest request,
                                                               @RequestBody HashMap<String, Object> params) {
        User user = getUserFromSession(request);
        if (user == null || user.getUserId() == null) {
            return ApiResponse.unauthorized("用户未登录");
        }
        try {
            Object rawStrategy = params != null ? params.get("strategy") : null;
            String json = rawStrategy instanceof String
                    ? rawStrategy.toString() : JsonUtil.toJsonString(rawStrategy);
            ComponentClassNameStrategy strategy = parseComponentClassNameStrategy(json);
            String sessionKey = params != null && params.get("sessionKey") != null
                    ? params.get("sessionKey").toString() : "configuration-preview";
            Object rawComponents = params != null ? params.get("components") : null;
            Collection<?> components = rawComponents instanceof Collection<?>
                    ? (Collection<?>) rawComponents
                    : List.of("BasicInfoComponent", "ExecCommandComponent", "FileComponent");
            if (components.size() > 100) {
                return ApiResponse.badRequest("单次最多预览100个组件类名");
            }
            Map<String, String> preview = new LinkedHashMap<>();
            for (Object component : components) {
                if (component == null || component.toString().isBlank()) continue;
                String name = component.toString().trim();
                preview.put(name, strategy.resolve(sessionKey, name));
            }
            return ApiResponse.success(preview);
        } catch (Exception e) {
            return ApiResponse.badRequest("类名画像配置错误: " + e.getMessage());
        }
    }
    
    @RequestMapping(value = "/puppets/delete", method = RequestMethod.POST)
    public HashMap<String, Object> deletePuppet(HttpServletRequest request, @RequestBody HashMap<String, Object> params) {
        if (params == null) {
            return ApiResponse.badRequest("params参数不能为空");
        }
        User user = getUserFromSession(request);
        if (user == null) {
            return ApiResponse.unauthorized("用户未登录");
        }
        String puppetId = (String) params.get(PARAM_PUPPET_ID_LOWER);
        if (puppetId == null || puppetId.isBlank()) {
            return ApiResponse.badRequest("puppetId不能为空");
        }
        Puppet puppet = puppetService.findPuppetById(puppetId);
        if (puppet == null) {
            return ApiResponse.notFound("Puppet不存在");
        }
        if (!hasPermissionToModify(puppet, user)) {
            return ApiResponse.forbidden("无权限删除此Puppet");
        }
        boolean result = puppetService.deletePuppetById(puppetId);
        if (result) {
            return ApiResponse.success();
        } else {
            return ApiResponse.error("删除Puppet失败");
        }
    }

    /**
     * URL 路径池自动探测接口。
     * 从平台侧请求目标站点，发现可用于 URL 随机化的真实路径。
     */
    @RequestMapping(value = "/url-probe", method = RequestMethod.POST)
    public HashMap<String, Object> probeUrlPaths(HttpServletRequest request, @RequestBody HashMap<String, Object> params) {
        User user = getUserFromSession(request);
        if (user == null) {
            return ApiResponse.unauthorized("用户未登录");
        }
        String baseUrl = (String) params.get("baseUrl");
        if (baseUrl == null || baseUrl.isBlank()) {
            return ApiResponse.badRequest("baseUrl 不能为空");
        }
        Number timeout = (Number) params.get("timeout");
        try {
            Map<String, Object> probeResult = urlProbeService.probe(baseUrl.trim(), timeout != null ? timeout.intValue() : 0);
            return ApiResponse.success(probeResult);
        } catch (Exception e) {
            logger.error("URL 探测失败, baseUrl={}: {}", baseUrl, e.getMessage());
            return ApiResponse.error("URL 探测失败: " + e.getMessage());
        }
    }

    /**
     * 从会话中获取用户
     */
    private User getUserFromSession(HttpServletRequest request) {
        return (User) request.getSession().getAttribute(SESSION_ATTR_USER);
    }

    private List<Puppet> filterVisiblePuppets(List<Puppet> puppets, User user) {
        if (puppets == null || puppets.isEmpty()) {
            return new ArrayList<Puppet>();
        }
        List<Puppet> visible = new ArrayList<Puppet>();
        for (Puppet puppet : puppets) {
            if (canViewPuppet(puppet, user)) {
                visible.add(puppet);
            }
        }
        return visible;
    }

    private boolean canViewPuppet(Puppet puppet, User user) {
        if (puppet == null || user == null || user.getUserId() == null) {
            return false;
        }
        if (isAdmin(user)) {
            return true;
        }
        if (user.getUserId().equals(puppet.getCreateByUserId())) {
            return true;
        }
        if (PERMISSION_PUBLIC.equals(puppet.getPermission())) {
            return true;
        }
        return isSameTeamPuppet(puppet, user) && isTeamVisiblePermission(puppet.getPermission());
    }
    
    /**
     * 检查是否有权限修改Puppet
     */
    private boolean hasPermissionToModify(Puppet puppet, User user) {
        if (puppet == null || user == null || user.getUserId() == null) {
            return false;
        }
        if (PERMISSION_PUBLIC.equals(puppet.getPermission())) {
            return isAdmin(user);
        }
        if (puppet.getCreateByUserId() != null &&
            puppet.getCreateByUserId().equals(user.getUserId())) {
            return true;
        }
        if (isAdmin(user)) {
            return true;
        }
        return PRIVILEGE_LEADER.equals(user.getPrivilege())
                && isTeamVisiblePermission(puppet.getPermission())
                && isSameTeamPuppet(puppet, user);
    }

    private boolean isAdmin(User user) {
        return user != null && PRIVILEGE_ADMIN.equals(user.getPrivilege());
    }

    private boolean isTeamVisiblePermission(String permission) {
        return PERMISSION_TEAM.equals(permission);
    }

    private boolean isSameTeamPuppet(Puppet puppet, User user) {
        if (puppet == null || user == null || user.getTeamId() == null || user.getTeamId().isBlank()) {
            return false;
        }
        if (user.getTeamId().equals(puppet.getTeamId())) {
            return true;
        }
        if (puppet.getTeamId() != null && !puppet.getTeamId().isBlank()) {
            return false;
        }
        User owner = userService.getUserById(puppet.getCreateByUserId());
        return owner != null && user.getTeamId().equals(owner.getTeamId());
    }

    private String normalizePuppetPermission(String permission) {
        if (PERMISSION_PUBLIC.equals(permission)) {
            return PERMISSION_PUBLIC;
        }
        if (PERMISSION_TEAM.equals(permission)) {
            return PERMISSION_TEAM;
        }
        return PERMISSION_PRIVATE;
    }

    private String resolvePuppetTeamId(Puppet puppet, User user, Puppet existingPuppet) {
        if (isAdmin(user)) {
            String requestedTeamId = puppet.getTeamId();
            if (requestedTeamId != null && !requestedTeamId.isBlank()) {
                return requestedTeamId.trim();
            }
            return existingPuppet != null ? existingPuppet.getTeamId() : user.getTeamId();
        }
        if (user.getTeamId() != null && !user.getTeamId().isBlank()) {
            return user.getTeamId();
        }
        return existingPuppet != null ? existingPuppet.getTeamId() : null;
    }

    private void validateComponentClassNameStrategy(String json) {
        if (json == null || json.isBlank()) return;
        ComponentClassNameStrategy strategy = parseComponentClassNameStrategy(json);
        strategy.validateConfiguration();
    }

    private ComponentClassNameStrategy parseComponentClassNameStrategy(String json) {
        if (json == null || json.isBlank() || "null".equals(json.trim())) {
            return new ComponentClassNameStrategy();
        }
        try {
            ComponentClassNameStrategy strategy = (ComponentClassNameStrategy) JsonUtil.fromJsonString(
                    json, ComponentClassNameStrategy.class);
            if (strategy == null) {
                throw new IllegalArgumentException("类名画像配置为空");
            }
            return strategy;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("类名画像 JSON 格式错误: " + e.getMessage(), e);
        }
    }
}
