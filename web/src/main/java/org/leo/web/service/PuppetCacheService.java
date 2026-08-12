package org.leo.web.service;

import org.leo.core.repository.session.PuppetHostCacheRepository;
import org.leo.web.dto.puppetnode.CacheCheckResponse;
import org.leo.web.exception.ApiException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** HostId-scoped puppet cache operations. */
@Service
public class PuppetCacheService {

    private final PuppetHostCacheRepository repository;

    public PuppetCacheService(PuppetHostCacheRepository repository) {
        this.repository = repository;
    }

    public CacheCheckResponse status(String userId, String puppetId) {
        boolean available = repository.hasPuppetCache(userId, puppetId);
        return new CacheCheckResponse(available,
                available ? repository.getPuppetCacheSaveTime(userId, puppetId) : null);
    }

    public List<String> hostIds(String userId, String puppetId) {
        return repository.listCachedHostIds(userId, puppetId);
    }

    public String requireSelectedHostId(String userId, String puppetId, String selectedHostId) {
        List<String> hostIds = hostIds(userId, puppetId);
        if (hostIds.isEmpty()) throw ApiException.badRequest("无本地缓存，无法进入缓存模式");
        String selected = selectedHostId == null || selectedHostId.isBlank() ? null : selectedHostId.trim();
        if (selected == null && hostIds.size() == 1) return hostIds.get(0);
        if (selected == null) throw ApiException.badRequest("请选择要进入的缓存后端主机");
        if (!hostIds.contains(selected)) throw ApiException.badRequest("指定的缓存后端主机不存在");
        return selected;
    }

    public Map<String, Object> loadBasicInfo(String userId, String puppetId, String hostId) {
        return repository.loadBasicInfo(userId, puppetId, hostId);
    }

    public void saveBasicInfo(String sessionId, String hostId, Map<String, Object> basicInfo) {
        repository.saveBasicInfo(sessionId, hostId, basicInfo);
    }
}
