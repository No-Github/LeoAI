package org.leo.service.shell;

import org.leo.jmg.generation.CoreArtifact;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 服务端 CoreArtifact 临时存储；Core 字节码不会返回给 LLM。 */
@Component
public class CoreArtifactStore {

    private static final long TTL_MS = 30 * 60 * 1000L;
    private static final int MAX_ENTRIES = 256;

    private static final class Entry {
        private final CoreArtifact artifact;
        private final long createdAt = System.currentTimeMillis();
        private final long expiresAt = createdAt + TTL_MS;

        private Entry(CoreArtifact artifact) {
            this.artifact = artifact;
        }
    }

    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();

    public String put(CoreArtifact artifact) {
        if (artifact == null) throw new IllegalArgumentException("CoreArtifact 不能为空");
        evictExpired();
        evictOldestIfFull();
        String id = UUID.randomUUID().toString();
        store.put(id, new Entry(artifact));
        return id;
    }

    public CoreArtifact get(String id) {
        if (id == null || id.isBlank()) return null;
        evictExpired();
        Entry entry = store.get(id.trim());
        return entry == null ? null : entry.artifact;
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
