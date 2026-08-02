package org.leo.service.shell;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 已通过契约校验的 AI Wrapper 模板临时存储。 */
@Component
public class WebShellWrapperTemplateStore {

    private static final long TTL_MS = 30 * 60 * 1000L;
    private static final int MAX_ENTRIES = 256;

    public static final class TemplateEntry {
        private final String template;
        private final String artifactType;
        private final String protocol;

        private TemplateEntry(String template, String artifactType, String protocol) {
            this.template = template;
            this.artifactType = artifactType;
            this.protocol = protocol;
        }

        public String getTemplate() {
            return template;
        }

        public String getArtifactType() {
            return artifactType;
        }

        public String getProtocol() {
            return protocol;
        }
    }

    private static final class StoredEntry {
        private final TemplateEntry template;
        private final long createdAt = System.currentTimeMillis();
        private final long expiresAt = createdAt + TTL_MS;

        private StoredEntry(TemplateEntry template) {
            this.template = template;
        }
    }

    private final ConcurrentHashMap<String, StoredEntry> store = new ConcurrentHashMap<>();

    public String put(String template, String artifactType, String protocol) {
        if (template == null || template.isBlank()) {
            throw new IllegalArgumentException("Wrapper 模板不能为空");
        }
        evictExpired();
        evictOldestIfFull();
        String id = UUID.randomUUID().toString();
        store.put(id, new StoredEntry(new TemplateEntry(template, artifactType, protocol)));
        return id;
    }

    public TemplateEntry get(String id) {
        if (id == null || id.isBlank()) return null;
        evictExpired();
        StoredEntry entry = store.get(id.trim());
        return entry == null ? null : entry.template;
    }

    private void evictExpired() {
        long now = System.currentTimeMillis();
        store.entrySet().removeIf(entry -> entry.getValue().expiresAt < now);
    }

    private void evictOldestIfFull() {
        while (store.size() >= MAX_ENTRIES) {
            String oldest = store.entrySet().stream()
                    .min(Comparator.comparingLong(entry -> entry.getValue().createdAt))
                    .map(Map.Entry::getKey)
                    .orElse(null);
            if (oldest == null) return;
            store.remove(oldest);
        }
    }
}
