package org.leo.core.repository.session;

import org.leo.core.session.PuppetNodeSession;
import org.leo.core.session.PuppetNodeSessionContainer;
import org.leo.core.util.json.JsonUtil;
import org.leo.core.util.session.PuppetNodeSessionWorkDirUtil;
import org.springframework.stereotype.Repository;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Base64;

/** Persistent puppet-level cache owned by HostId and discovery concerns. */
@Repository
public class PuppetHostCacheRepository {

    private static final String BASIC_INFO_SUBDIR = "basic-info";
    private static final String HOST_DISCOVERY_JSON = "host-discovery.json";
    private static final String SAVE_TIME_KEY = "saveTime";

    public File saveBasicInfo(String sessionId, String hostId, Map<String, Object> basicInfo) {
        if (sessionId == null || sessionId.isBlank() || hostId == null || hostId.isBlank() || basicInfo == null) {
            return null;
        }
        try {
            PuppetNodeSession session = requireSession(sessionId);
            File hostDir = new File(PuppetNodeSessionWorkDirUtil.getPuppetWorkDir(
                    session.getCreateByUser(), session.resolvePuppetId()), BASIC_INFO_SUBDIR);
            if (!hostDir.exists()) hostDir.mkdirs();
            Map<String, Object> root = new LinkedHashMap<>();
            root.putAll(basicInfo);
            root.put("hostId", hostId.trim());
            root.put(SAVE_TIME_KEY, Instant.now().toString());
            return writeJson(new File(hostDir, encodeHostId(hostId.trim()) + ".json"), root);
        } catch (Exception e) {
            return null;
        }
    }

    public Map<String, Object> loadBasicInfo(String userId, String puppetId, String hostId) {
        if (puppetId == null || puppetId.isBlank() || hostId == null || hostId.isBlank()) return null;
        try {
            File file = new File(new File(PuppetNodeSessionWorkDirUtil.getPuppetWorkDir(userId, puppetId), BASIC_INFO_SUBDIR),
                    encodeHostId(hostId.trim()) + ".json");
            return readJsonMap(file);
        } catch (Exception e) {
            return null;
        }
    }

    public Map<String, Object> loadBasicInfo(String sessionId) {
        try {
            PuppetNodeSession session = requireSession(sessionId);
            return loadBasicInfo(session.getCreateByUser(), session.resolvePuppetId(), session.getCurrentHostId());
        } catch (Exception e) {
            return null;
        }
    }

    public List<String> listCachedHostIds(String userId, String puppetId) {
        if (puppetId == null || puppetId.isBlank()) return List.of();
        try {
            File hostDir = new File(PuppetNodeSessionWorkDirUtil.getPuppetWorkDir(userId, puppetId), BASIC_INFO_SUBDIR);
            File[] files = hostDir.listFiles((dir, name) -> name.endsWith(".json"));
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            if (files != null) {
                for (File file : files) {
                    Map<String, Object> data = readJsonMap(file);
                    String id = text(data == null ? null : data.get("hostId"));
                    if (id != null) ids.add(id);
                }
            }
            return List.copyOf(ids);
        } catch (Exception e) {
            return List.of();
        }
    }

    public boolean hasPuppetCache(String userId, String puppetId) {
        return !listCachedHostIds(userId, puppetId).isEmpty();
    }

    public String getPuppetCacheSaveTime(String userId, String puppetId) {
        try {
            File hostDir = new File(PuppetNodeSessionWorkDirUtil.getPuppetWorkDir(userId, puppetId), BASIC_INFO_SUBDIR);
            File[] files = hostDir.listFiles((dir, name) -> name.endsWith(".json") && new File(dir, name).length() > 0);
            if (files == null || files.length == 0) return null;
            File newest = files[0];
            for (File file : files) if (file.lastModified() > newest.lastModified()) newest = file;
            Map<String, Object> data = readJsonMap(newest);
            return text(data == null ? null : data.get(SAVE_TIME_KEY));
        } catch (Exception e) {
            return null;
        }
    }

    public void saveHostDiscovery(String userId, String puppetId, String fingerprint, List<String> hostIds) {
        if (puppetId == null || puppetId.isBlank()) return;
        List<String> normalized = new ArrayList<>();
        if (hostIds != null) for (String id : hostIds) if (text(id) != null) normalized.add(text(id));
        if (normalized.isEmpty()) return;
        try {
            File file = new File(PuppetNodeSessionWorkDirUtil.getPuppetWorkDir(userId, puppetId), HOST_DISCOVERY_JSON);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("hostIds", List.copyOf(new LinkedHashSet<>(normalized)));
            data.put("discoveredAt", Instant.now().toString());
            data.put("fingerprint", fingerprint == null ? "" : fingerprint);
            writeJson(file, data);
        } catch (Exception ignored) { }
    }

    public Map<String, Object> loadHostDiscovery(String userId, String puppetId, String fingerprint) {
        try {
            File file = new File(PuppetNodeSessionWorkDirUtil.getPuppetWorkDir(userId, puppetId), HOST_DISCOVERY_JSON);
            Map<String, Object> data = readJsonMap(file);
            String expected = fingerprint == null ? "" : fingerprint;
            if (data == null || !expected.equals(String.valueOf(data.getOrDefault("fingerprint", "")))) return null;
            Object raw = data.get("hostIds");
            if (!(raw instanceof Collection<?>)) return null;
            LinkedHashSet<String> hosts = new LinkedHashSet<>();
            for (Object value : (Collection<?>) raw) if (text(value) != null) hosts.add(text(value));
            data.put("hostIds", List.copyOf(hosts));
            return data;
        } catch (Exception e) {
            return null;
        }
    }

    private PuppetNodeSession requireSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId 不能为空");
        PuppetNodeSession session = PuppetNodeSessionContainer.getSession(sessionId);
        if (session == null || session.resolvePuppetId() == null) throw new IllegalStateException("session 未绑定 puppetId");
        return session;
    }

    private File writeJson(File file, Map<String, Object> data) throws Exception {
        Files.write(file.toPath(), JsonUtil.toJsonString(data).getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return file;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJsonMap(File file) {
        try {
            if (file == null || !file.exists() || file.length() == 0) return null;
            return (Map<String, Object>) JsonUtil.fromJsonString(
                    Files.readString(file.toPath(), StandardCharsets.UTF_8), LinkedHashMap.class);
        } catch (Exception e) {
            return null;
        }
    }

    private String encodeHostId(String hostId) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hostId.getBytes(StandardCharsets.UTF_8));
    }

    private String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }
}
