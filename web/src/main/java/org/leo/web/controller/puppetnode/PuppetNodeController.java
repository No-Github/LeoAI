package org.leo.web.controller.puppetnode;

import jakarta.servlet.http.HttpServletRequest;
import org.leo.core.entity.Puppet;
import org.leo.core.entity.User;
import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.core.puppet.capability.BasicInfoCapable;
import org.leo.core.puppet.capability.PuppetNodeCapabilityRegistry;
import org.leo.core.session.PuppetNodeSession;
import org.leo.core.util.ApiResponse;
import org.leo.core.util.session.PuppetNodeSessionWorkDirUtil;
import org.leo.web.dto.puppetnode.CacheCheckResponse;
import org.leo.web.dto.puppetnode.PuppetIdRequest;
import org.leo.web.dto.puppetnode.SessionIdRequest;
import org.leo.service.PuppetConnService;
import org.leo.web.security.PermissionService;
import org.leo.web.service.PuppetNodeLifecycleService;
import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/puppet-node")
public class PuppetNodeController {

    private final PuppetConnService puppetConnService;
    private final PermissionService permissionService;
    private final PuppetNodeLifecycleService puppetNodeLifecycleService;

    public PuppetNodeController(PuppetConnService puppetConnService,
                                PermissionService permissionService,
                                PuppetNodeLifecycleService puppetNodeLifecycleService) {
        this.puppetConnService = puppetConnService;
        this.permissionService = permissionService;
        this.puppetNodeLifecycleService = puppetNodeLifecycleService;
    }

    /**
     * 初始化 Puppet，建立会话。
     */
    @RequestMapping(value = "/init", method = RequestMethod.GET)
    public HashMap<String, Object> initPuppet(HttpServletRequest request,
                                              @RequestParam("puppetId") String puppetId) throws Exception {
        User user = permissionService.requireLogin(request);
        Puppet puppet = permissionService.requireAccessiblePuppetChain(puppetId, user);
        return ApiResponse.success(puppetNodeLifecycleService.initLiveSession(puppet, user));
    }

    /**
     * 检查指定 puppet 是否存在本地缓存。
     * 返回 hasCache(boolean)、saveTime(String, 可能为 null)。
     */
    @RequestMapping(value = "/check-cache", method = RequestMethod.GET)
    public Map<String, Object> checkCache(HttpServletRequest request,
                                          @RequestParam("puppetId") String puppetId) {
        User user = permissionService.requireLogin(request);
        Puppet puppet = permissionService.requireAccessiblePuppetChain(puppetId, user);

        String userId = user.getUserId();
        boolean hasCache = PuppetNodeSessionWorkDirUtil.hasPuppetCache(userId, puppet.getPuppetId());
        String saveTime = hasCache ? PuppetNodeSessionWorkDirUtil.getPuppetCacheSaveTime(userId, puppet.getPuppetId()) : null;

        return ApiResponse.success(new CacheCheckResponse(hasCache, saveTime));
    }

    /**
     * 以缓存模式进入 puppet，不尝试实时连接。
     * 要求 puppet 存在且有本地缓存，否则返回错误。
     * 返回 sessionId、cacheMode=true。
     */
    @RequestMapping(value = "/init-cache", method = RequestMethod.GET)
    public Map<String, Object> initCache(HttpServletRequest request,
                                         @RequestParam("puppetId") String puppetId) {
        User user = permissionService.requireLogin(request);
        Puppet puppet = permissionService.requireAccessiblePuppetChain(puppetId, user);
        return ApiResponse.success(puppetNodeLifecycleService.initCacheSession(puppet, user));
    }

    /**
     * 测试 Puppet 连通性，不创建会话。
     */
    @RequestMapping(value = "/test-conn", method = RequestMethod.POST)
    public Map<String, Object> testConnCheck(HttpServletRequest request, @RequestBody PuppetIdRequest body) {
        User user = permissionService.requireLogin(request);
        String puppetId = body != null ? body.puppetId() : null;
        Puppet puppet = permissionService.requireAccessiblePuppetChain(puppetId, user);
        Map<String, Object> result = puppetConnService.testConnection(puppet.getPuppetId());
        return ApiResponse.success(result);
    }

    /**
     * 获取基础信息。
     */
    @RequestMapping(value = "/basic-info", method = RequestMethod.POST)
    public Map<String, Object> basicInfo(@RequestBody SessionIdRequest body) throws Exception {
        String sessionId = body != null ? body.sessionId() : null;
        PuppetNodeSession session = ControllerUtil.getPuppetNodeSession(sessionId);

        // 缓存模式：直接从 puppet 级持久化目录读取，不尝试实时连接
        if (session != null && session.isCacheMode()) {
            String pId = session.getPuppetId();
            Map<String, Object> cached = PuppetNodeSessionWorkDirUtil.loadBasicInfo(
                    session.getCreateByUser(), pId);
            if (cached != null) {
                Map<String, Object> result = new HashMap<>();
                result.put("BasicInfo", cached);
                return ApiResponse.success(result);
            }
            return ApiResponse.success(new HashMap<>());
        }

        BasicInfoCapable node = ControllerUtil.requireCapability(sessionId, BasicInfoCapable.class);
        Map<String, Object> results = node.getBasicInfo();

        if (session != null && results != null && results.containsKey("BasicInfo")) {
            Object basicInfoObj = results.get("BasicInfo");
            if (basicInfoObj instanceof Map<?, ?> basicInfoMap) {
                Map<String, Object> basicInfo = copyStringKeyMap(basicInfoMap);
                String hostId = session.getCurrentHostId();
                if (hostId != null) {
                    session.setBasicInfo(hostId, basicInfo);
                    PuppetNodeSessionWorkDirUtil.saveBasicInfo(sessionId, hostId, basicInfo);
                }
            }
        }
        return ApiResponse.success(results != null ? results : new HashMap<>());
    }

    private static Map<String, Object> copyStringKeyMap(Map<?, ?> source) {
        Map<String, Object> copy = new HashMap<>();
        source.forEach((key, value) -> {
            if (key instanceof String stringKey) copy.put(stringKey, value);
        });
        return copy;
    }

    /**
     * 获取当前主机信息。
     */
    @RequestMapping(value = "/current-host", method = RequestMethod.POST)
    public Map<String, Object> getCurrentHost(@RequestBody SessionIdRequest body) {
        String sessionId = body != null ? body.sessionId() : null;
        AbstractPuppetNode node = ControllerUtil.getAbstractPuppetNode(sessionId);
        Puppet currentPuppet = node.getPuppet();
        return ApiResponse.success(currentPuppet);
    }

    /**
     * 获取当前会话支持的能力清单。
     */
    @RequestMapping(value = "/capabilities", method = RequestMethod.POST)
    public Map<String, Object> capabilities(@RequestBody SessionIdRequest body) {
        String sessionId = body != null ? body.sessionId() : null;
        PuppetNodeSession session = ControllerUtil.getPuppetNodeSession(sessionId);
        AbstractPuppetNode node = session.getPuppetNode();
        List<String> capabilities = PuppetNodeCapabilityRegistry.listSupported(node);

        Map<String, Object> result = new HashMap<>();
        result.put("sessionId", session.getSessionId());
        result.put("cacheMode", session.isCacheMode());
        result.put("capabilities", capabilities);
        result.put("capabilityCount", capabilities.size());
        if (node != null && node.getPuppet() != null) {
            result.put("puppetId", node.getPuppet().getPuppetId());
            result.put("puppetType", node.getPuppet().getType());
        } else if (session.getPuppetId() != null) {
            result.put("puppetId", session.getPuppetId());
        }
        return ApiResponse.success(result);
    }
}
