package org.leo.web.service;

import org.leo.core.entity.Puppet;
import org.leo.core.entity.User;
import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.core.repository.session.PuppetHostCacheRepository;
import org.leo.service.puppetnode.PuppetNodeFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Discovers the backend instances hidden behind one load-balanced session endpoint. */
@Service
public class PuppetHostDiscoveryService {

    static final int DEFAULT_PROBE_COUNT = 8;
    private final PuppetNodeFactory puppetNodeFactory;
    private final PuppetHostCacheRepository repository;

    public record DiscoveryResult(List<String> hostIds, String discoveredAt, boolean reused) { }

    public PuppetHostDiscoveryService(PuppetNodeFactory puppetNodeFactory, PuppetHostCacheRepository repository) {
        this.puppetNodeFactory = puppetNodeFactory;
        this.repository = repository;
    }

    /** 按连接配置复用已登记 HostId；forceRefresh 仅由用户主动刷新时使用。 */
    public DiscoveryResult discover(Puppet puppet, User user, boolean forceRefresh) throws Exception {
        String fingerprint = connectionFingerprint(puppet);
        Map<String, Object> saved = load(puppet, user, fingerprint);
        if (!forceRefresh) {
            List<String> known = hostIdsFrom(saved);
            if (!known.isEmpty()) return result(known, saved, true);
        }
        AbstractPuppetNode probeNode = puppetNodeFactory.createLiveNode(puppet, user);
        LinkedHashSet<String> discovered = new LinkedHashSet<>();
        try {
            for (int probe = 0; probe < DEFAULT_PROBE_COUNT; probe++) {
                if (probe > 0) {
                    try { probeNode.close(); } catch (Exception ignored) { }
                    probeNode = puppetNodeFactory.createLiveNode(puppet, user);
                }
                Map<String, Object> result = probeNode.testConnection();
                if (!isSuccess(result)) break;
                String hostId = normalized(result.get("hostId"));
                if (hostId != null) discovered.add(hostId);
            }
            List<String> result = List.copyOf(discovered);
            repository.saveHostDiscovery(
                    user != null ? user.getUserId() : null, puppet.getPuppetId(), fingerprint, result);
            return result(result, load(puppet, user, fingerprint), false);
        } finally {
            try { probeNode.close(); } catch (Exception ignored) { }
        }
    }

    private Map<String, Object> load(Puppet puppet, User user, String fingerprint) {
        return repository.loadHostDiscovery(
                user != null ? user.getUserId() : null, puppet.getPuppetId(), fingerprint);
    }

    private DiscoveryResult result(List<String> hostIds, Map<String, Object> details, boolean reused) {
        Object discoveredAt = details == null ? null : details.get("discoveredAt");
        return new DiscoveryResult(hostIds, discoveredAt == null ? null : String.valueOf(discoveredAt), reused);
    }

    public List<String> known(Puppet puppet, User user) {
        return known(puppet, user != null ? user.getUserId() : null);
    }

    public List<String> known(Puppet puppet, String userId) {
        return hostIdsFrom(repository.loadHostDiscovery(
                userId, puppet.getPuppetId(), connectionFingerprint(puppet)));
    }

    public Map<String, Object> knownDetails(Puppet puppet, User user) {
        return repository.loadHostDiscovery(
                user != null ? user.getUserId() : null, puppet.getPuppetId(), connectionFingerprint(puppet));
    }

    private List<String> hostIdsFrom(Map<String, Object> data) {
        if (data == null || !(data.get("hostIds") instanceof Collection<?> raw)) return List.of();
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        raw.forEach(value -> { String id = normalized(value); if (id != null) ids.add(id); });
        return List.copyOf(ids);
    }

    private String connectionFingerprint(Puppet puppet) {
        String source = String.valueOf(puppet.getConnLink()) + "|" + String.valueOf(puppet.getProtocol())
                + "|" + String.valueOf(puppet.getHeaders()) + "|" + String.valueOf(puppet.getProxyType())
                + "|" + String.valueOf(puppet.getProxyHost()) + "|" + String.valueOf(puppet.getProxyPort());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte value : digest) hex.append(String.format("%02x", value));
            return hex.toString();
        } catch (Exception e) { return source; }
    }

    private boolean isSuccess(Map<String, Object> result) {
        if (result == null) return false;
        Object code = result.get("code");
        if (code instanceof Number number) return number.intValue() == 200;
        return "200".equals(String.valueOf(code));
    }

    private String normalized(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

}
