package org.leo.core.repository.session;

import org.leo.core.util.session.PuppetNodeSessionWorkDirUtil;
import org.springframework.stereotype.Repository;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Persistence boundary for Spring AI thread checkpoints. */
@Repository
public class PuppetAiCheckpointRepository {

    private static final String CHECKPOINTS_SUBDIR = "checkpoints";
    private static final ConcurrentHashMap<String, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    public File getCheckpointDir(String userId, String puppetId, String threadId) {
        return checkpointDir(userId, puppetId, threadId, true);
    }

    public void delete(String userId, String puppetId, String threadId) {
        if (puppetId == null || threadId == null) return;
        ReentrantLock lock = LOCKS.computeIfAbsent(key(userId, puppetId, threadId), ignored -> new ReentrantLock());
        lock.lock();
        try { deleteDir(checkpointDir(userId, puppetId, threadId, false).toPath()); }
        finally { lock.unlock(); }
    }

    public boolean exists(String userId, String puppetId, String threadId) {
        if (puppetId == null || threadId == null) return false;
        File dir = checkpointDir(userId, puppetId, threadId, false);
        File[] files = dir.listFiles();
        return files != null && files.length > 0;
    }

    private File checkpointDir(String userId, String puppetId, String threadId, boolean create) {
        File dir = new File(new File(PuppetNodeSessionWorkDirUtil.getAiThreadsDir(userId, puppetId), CHECKPOINTS_SUBDIR),
                safeThreadName(threadId));
        if (create && !dir.exists()) dir.mkdirs();
        return dir;
    }

    private String key(String userId, String puppetId, String threadId) {
        return String.valueOf(userId).trim() + ':' + String.valueOf(puppetId).trim() + ':' + String.valueOf(threadId).trim();
    }

    private String safeThreadName(String threadId) {
        String safe = threadId.replaceAll("[^a-zA-Z0-9\\-_]", "_");
        return safe.length() > 64 ? safe.substring(0, 64) : safe;
    }

    private boolean deleteDir(Path path) {
        try {
            if (!Files.exists(path)) return true;
            try (var walk = Files.walk(path)) {
                walk.sorted(Comparator.reverseOrder()).forEach(item -> {
                    try { Files.deleteIfExists(item); } catch (IOException error) { throw new RuntimeException(error); }
                });
            }
            return true;
        } catch (Exception e) { return false; }
    }
}
