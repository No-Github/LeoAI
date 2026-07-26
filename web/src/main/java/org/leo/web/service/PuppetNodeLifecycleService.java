package org.leo.web.service;

import org.leo.core.entity.Puppet;
import org.leo.core.entity.User;
import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.core.puppet.capability.HostScopedCapable;
import org.leo.core.puppet.capability.LoadedComponentCacheCapable;
import org.leo.core.session.PuppetNodeSession;
import org.leo.core.session.PuppetNodeSessionContainer;
import org.leo.core.util.session.PuppetNodeSessionWorkDirUtil;
import org.leo.service.PuppetService;
import org.leo.service.puppetnode.PuppetNodeFactory;
import org.leo.web.dto.puppetnode.PuppetInitResponse;
import org.leo.web.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Puppet 会话生命周期服务。
 *
 * <p>把连接构建、会话创建、AI 初始线程等流程从 Controller 中拆出，
 * Controller 只保留 HTTP 参数和响应编排。
 */
@Service
public class PuppetNodeLifecycleService {

    private static final Logger logger = LoggerFactory.getLogger(PuppetNodeLifecycleService.class);

    private final PuppetService puppetService;
    private final PuppetNodeFactory puppetNodeFactory;
    private final PuppetNodeAiThreadService aiThreadService;

    public PuppetNodeLifecycleService(PuppetService puppetService,
                                      PuppetNodeFactory puppetNodeFactory,
                                      PuppetNodeAiThreadService aiThreadService) {
        this.puppetService = puppetService;
        this.puppetNodeFactory = puppetNodeFactory;
        this.aiThreadService = aiThreadService;
    }

    public PuppetInitResponse initLiveSession(Puppet puppet, User user) throws Exception {
        String sessionId = UUID.randomUUID().toString();

        AbstractPuppetNode node = puppetNodeFactory.createLiveNode(puppet, user);
        boolean connectionOk = doInitConn(node, sessionId, user != null ? user.getUserId() : null);
        if (!connectionOk) {
            logger.warn("Puppet初始化失败，无主机回复，puppetId: {}", puppet.getPuppetId());
            throw ApiException.serverError("Puppet初始化失败，无主机回复");
        }

        puppetService.updateLastHeartbeat(puppet.getPuppetId());
        logger.info("Puppet初始化成功，puppetId: {}, sessionId: {}", puppet.getPuppetId(), sessionId);
        PuppetNodeSession session = PuppetNodeSessionContainer.getSession(sessionId);
        return new PuppetInitResponse(sessionId, false, session != null ? session.getCapabilities() : List.of());
    }

    public PuppetInitResponse initCacheSession(Puppet puppet, User user) {
        String userId = user.getUserId();
        String puppetId = puppet.getPuppetId();
        if (!PuppetNodeSessionWorkDirUtil.hasPuppetCache(userId, puppetId)) {
            throw ApiException.badRequest("无本地缓存，无法进入缓存模式");
        }

        String sessionId = UUID.randomUUID().toString();
        PuppetNodeSession session = new PuppetNodeSession(sessionId, null,
                System.currentTimeMillis(), userId);
        session.setCacheMode(true);
        session.setPuppetId(puppetId);

        try {
            String savedSummary = PuppetNodeSessionWorkDirUtil.loadReconSummary(userId, puppetId);
            if (savedSummary != null) {
                session.setReconSummary(savedSummary);
            }
        } catch (Exception ex) {
            logger.warn("缓存模式回填数据失败, puppetId={}: {}", puppetId, ex.getMessage());
        }

        registerSessionWithInitialAiThread(session, puppetId);

        logger.info("缓存模式 session 已创建, puppetId={}, sessionId={}", puppetId, sessionId);
        return new PuppetInitResponse(sessionId, true, session.getCapabilities());
    }

    private boolean doInitConn(AbstractPuppetNode node, String sessionId, String userId) throws Exception {
        Puppet puppet = node.getPuppet();
        int maxCount = resolveMaxReqCount(puppet);

        for (int i = 0; i < maxCount; i++) {
            Map<String, Object> result = node.testConnection();
            if (isConnectionSuccess(result)) {
                String hostId = parseHostId(result.get("hostId"));
                if (requiresHostId(node) && hostId == null) {
                    logger.debug("测试连接成功但缺少 hostId，继续尝试，sessionId={}", sessionId);
                    continue;
                }

                seedNodeContext(node, hostId, result.get("components"));

                PuppetNodeSession session = new PuppetNodeSession(sessionId, node,
                        System.currentTimeMillis(), userId);
                if (hostId != null) {
                    session.setCurrentHostId(hostId);
                }

                loadPersistedReconSummary(session, node, userId);
                registerSessionWithInitialAiThread(
                        session, puppet != null ? puppet.getPuppetId() : null);

                logger.debug("测试连接成功，hostId: {}, sessionId: {}", hostId, sessionId);
                // 无论是否负载均衡，首次成功即返回（避免重复创建 session）
                return true;
            }
        }
        return false;
    }

    private int resolveMaxReqCount(Puppet puppet) {
        Integer maxReqCount = puppet != null ? puppet.getMaxReqCount() : null;
        return maxReqCount != null && maxReqCount > 0 ? maxReqCount : 1;
    }

    private boolean isConnectionSuccess(Map<String, Object> result) {
        if (result == null) {
            return false;
        }
        Object code = result.get("code");
        if (code instanceof Number number) {
            return number.intValue() == 200;
        }
        return "200".equals(String.valueOf(code));
    }

    private boolean requiresHostId(AbstractPuppetNode node) {
        return node instanceof HostScopedCapable || node instanceof LoadedComponentCacheCapable;
    }

    private void seedNodeContext(AbstractPuppetNode node, String hostId, Object components) {
        if (hostId == null) {
            return;
        }
        if (node instanceof LoadedComponentCacheCapable componentCache) {
            componentCache.addLoadedComponent(hostId, parseLoadedComponents(components));
        }
        if (node instanceof HostScopedCapable hostScopedNode) {
            hostScopedNode.setHostId(hostId);
        }
    }

    private String parseHostId(Object value) {
        if (value == null) {
            return null;
        }
        String hostId = String.valueOf(value).trim();
        return hostId.isBlank() ? null : hostId;
    }

    private Set<String> parseLoadedComponents(Object components) {
        Set<String> result = new LinkedHashSet<>();
        if (components == null) {
            return result;
        }
        if (components instanceof Collection<?> collection) {
            for (Object item : collection) {
                addComponentName(result, item);
            }
            return result;
        }
        Class<?> type = components.getClass();
        if (type.isArray()) {
            int length = Array.getLength(components);
            for (int i = 0; i < length; i++) {
                addComponentName(result, Array.get(components, i));
            }
            return result;
        }
        addComponentName(result, components);
        return result;
    }

    private void addComponentName(Set<String> target, Object value) {
        if (value == null) {
            return;
        }
        String name = String.valueOf(value).trim();
        if (!name.isBlank()) {
            target.add(name);
        }
    }

    private void loadPersistedReconSummary(PuppetNodeSession session, AbstractPuppetNode node, String userId) {
        try {
            if (node.getPuppet() == null) {
                return;
            }
            String puppetId = node.getPuppet().getPuppetId();
            String savedSummary = PuppetNodeSessionWorkDirUtil.loadReconSummary(userId, puppetId);
            if (savedSummary != null) {
                session.setReconSummary(savedSummary);
                logger.debug("已回填侦察摘要, puppetId={}, length={}", puppetId, savedSummary.length());
            }
        } catch (Exception ex) {
            logger.warn("回填侦察摘要失败, sessionId={}: {}", session.getSessionId(), ex.getMessage());
        }
    }

    void registerSessionWithInitialAiThread(PuppetNodeSession session, String puppetId) {
        PuppetNodeSessionContainer.addSession(session.getSessionId(), session);
        try {
            // 与 /puppet-node/ai/thread/create 完全复用同一条创建链路：
            // 内存线程、数据库记录、模型配置和会话预热保持一致。
            aiThreadService.createThread(session, "对话 1", null, null);
        } catch (Exception ex) {
            logger.warn("创建初始 AI 线程失败, puppetId={}, sessionId={}: {}",
                    puppetId, session.getSessionId(), ex.getMessage());
        }
    }

}
