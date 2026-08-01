package org.leo.ai.agent;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具超大结果的短期归档。
 *
 * <p>归档按 memoryId 隔离，模型只能通过同一会话的分页工具读取，避免把
 * 一个会话的命令输出暴露给另一个会话。生产环境可在此接口后替换为持久化对象存储。
 */
@Component
public class AiToolResultArchive {

    private static final long DEFAULT_TTL_MS = 30 * 60 * 1000L;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    public String put(String memoryId, String toolName, String content,
                      Map<String, Object> metadata, long ttlMs) {
        String id = UUID.randomUUID().toString();
        entries.put(id, new Entry(
                memoryId != null ? memoryId : "<no-memory>",
                toolName,
                content != null ? content : "",
                metadata != null ? Map.copyOf(metadata) : Map.of(),
                System.currentTimeMillis() + Math.max(1_000L,
                        ttlMs > 0 ? ttlMs : DEFAULT_TTL_MS)));
        return id;
    }

    public ArchivePage page(String memoryId, String archiveId, int offset, int limit) {
        evictExpired();
        Entry entry = entries.get(archiveId);
        if (entry == null || !entry.memoryId.equals(memoryId != null ? memoryId : "<no-memory>")) {
            return null;
        }
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.min(8_000, Math.max(1, limit));
        if (safeOffset > entry.content.length()) safeOffset = entry.content.length();
        int end = Math.min(entry.content.length(), safeOffset + safeLimit);
        return new ArchivePage(
                archiveId,
                entry.toolName,
                entry.content.substring(safeOffset, end),
                safeOffset,
                end,
                entry.content.length(),
                end < entry.content.length(),
                entry.metadata);
    }

    public int size() {
        evictExpired();
        return entries.size();
    }

    private void evictExpired() {
        long now = System.currentTimeMillis();
        entries.entrySet().removeIf(entry -> entry.getValue().expiresAt < now);
    }

    private record Entry(String memoryId, String toolName, String content,
                         Map<String, Object> metadata, long expiresAt) {
    }

    public record ArchivePage(String archiveId, String toolName, String content,
                              int offset, int endOffset, int totalChars,
                              boolean hasMore, Map<String, Object> metadata) {
    }
}
