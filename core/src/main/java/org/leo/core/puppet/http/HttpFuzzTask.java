package org.leo.core.puppet.http;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe lifecycle state for one HTTP fuzzer task.
 *
 * <p>The transport engine owns task creation while workers only record their
 * result here. Keeping the lifecycle in a typed object avoids string-keyed
 * state mutations and lets a task finish even when no client is polling it.</p>
 */
final class HttpFuzzTask {

    static final String RUNNING = "RUNNING";
    static final String FINISHED = "FINISHED";
    static final String STOPPED = "STOPPED";

    private final String taskId;
    private final int total;
    private final long createdAt;
    private final AtomicInteger completed = new AtomicInteger();
    private final List<Map<String, Object>> results =
            Collections.synchronizedList(new ArrayList<Map<String, Object>>());

    private volatile String status = RUNNING;
    private volatile Long finishedAt;
    private ExecutorService executor;

    HttpFuzzTask(String taskId, int total, long createdAt) {
        this.taskId = taskId;
        this.total = total;
        this.createdAt = createdAt;
    }

    synchronized void attachExecutor(ExecutorService executor) {
        if (!RUNNING.equals(status)) {
            executor.shutdownNow();
            throw new IllegalStateException("fuzz task is not running: " + taskId);
        }
        this.executor = executor;
    }

    boolean isStopped() {
        return STOPPED.equals(status);
    }

    void record(Map<String, Object> result) {
        results.add(result);
        int done = completed.incrementAndGet();
        if (done >= total) {
            finish();
        }
    }

    synchronized boolean stop() {
        if (!RUNNING.equals(status)) {
            return false;
        }
        status = STOPPED;
        finishedAt = Long.valueOf(System.currentTimeMillis());
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        return true;
    }

    boolean isExpired(long now, long ttlMillis) {
        Long completedAt = finishedAt;
        return completedAt != null && !RUNNING.equals(status)
                && now - completedAt.longValue() > ttlMillis;
    }

    Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("code", Integer.valueOf(200));
        snapshot.put("taskId", taskId);
        snapshot.put("status", status);
        snapshot.put("total", Integer.valueOf(total));
        snapshot.put("completed", Integer.valueOf(completed.get()));
        synchronized (results) {
            snapshot.put("results", new ArrayList<Map<String, Object>>(results));
        }
        snapshot.put("createdAt", Long.valueOf(createdAt));
        snapshot.put("finishedAt", finishedAt);
        return snapshot;
    }

    private synchronized void finish() {
        if (!RUNNING.equals(status)) {
            return;
        }
        status = FINISHED;
        finishedAt = Long.valueOf(System.currentTimeMillis());
        executor = null;
    }
}
