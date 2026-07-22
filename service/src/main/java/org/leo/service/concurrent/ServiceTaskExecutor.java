package org.leo.service.concurrent;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Isolated, bounded execution domains for SQL export and file transfer tasks. */
@Component
public final class ServiceTaskExecutor implements AutoCloseable {

    private static final int SQL_THREADS = 4;
    private static final int SQL_QUEUE_CAPACITY = 32;
    private static final int UPLOAD_QUEUE_CAPACITY = 64;
    private static final int DOWNLOAD_THREADS = 32;
    private static final int DOWNLOAD_QUEUE_CAPACITY = 256;

    private final ThreadPoolExecutor sqlExecutor;
    private final ThreadPoolExecutor uploadExecutor;
    private final ThreadPoolExecutor downloadExecutor;

    public ServiceTaskExecutor() {
        this(SQL_THREADS, SQL_QUEUE_CAPACITY,
                defaultUploadThreads(), UPLOAD_QUEUE_CAPACITY,
                DOWNLOAD_THREADS, DOWNLOAD_QUEUE_CAPACITY);
    }

    ServiceTaskExecutor(int sqlThreads, int sqlQueueCapacity,
                        int uploadThreads, int uploadQueueCapacity,
                        int downloadThreads, int downloadQueueCapacity) {
        this.sqlExecutor = newExecutor(sqlThreads, sqlQueueCapacity, "sql-export-");
        this.uploadExecutor = newExecutor(uploadThreads, uploadQueueCapacity, "file-upload-");
        this.downloadExecutor = newExecutor(downloadThreads, downloadQueueCapacity, "file-download-");
    }

    public Future<?> submitSqlExport(Runnable task) {
        return sqlExecutor.submit(task);
    }

    public Future<?> submitUpload(Runnable task) {
        return uploadExecutor.submit(task);
    }

    public void cancelUpload(Future<?> future) {
        if (future != null) future.cancel(true);
        uploadExecutor.purge();
    }

    /**
     * Submits all workers for one download as a unit. If capacity is exhausted, every worker
     * accepted during this call is cancelled before the rejection is returned to the task.
     */
    public List<Future<?>> submitDownloadWorkers(int workerCount, Runnable worker) {
        if (workerCount < 1) {
            throw new IllegalArgumentException("download worker count must be positive");
        }
        List<Future<?>> accepted = new ArrayList<Future<?>>(workerCount);
        try {
            for (int index = 0; index < workerCount; index++) {
                accepted.add(downloadExecutor.submit(worker));
            }
            return accepted;
        } catch (RuntimeException error) {
            for (Future<?> future : accepted) future.cancel(true);
            downloadExecutor.purge();
            throw error;
        }
    }

    public void cancelDownloadWorkers(Collection<Future<?>> futures) {
        if (futures != null) {
            for (Future<?> future : futures) {
                if (future != null) future.cancel(true);
            }
        }
        downloadExecutor.purge();
    }

    int activeSqlTasks() {
        return sqlExecutor.getActiveCount();
    }

    int queuedSqlTasks() {
        return sqlExecutor.getQueue().size();
    }

    int activeUploadTasks() {
        return uploadExecutor.getActiveCount();
    }

    int queuedUploadTasks() {
        return uploadExecutor.getQueue().size();
    }

    int activeDownloadWorkers() {
        return downloadExecutor.getActiveCount();
    }

    int queuedDownloadWorkers() {
        return downloadExecutor.getQueue().size();
    }

    private static int defaultUploadThreads() {
        return Math.max(4, Math.min(8, Runtime.getRuntime().availableProcessors()));
    }

    private static ThreadPoolExecutor newExecutor(int threads, int queueCapacity, String prefix) {
        if (threads < 1 || queueCapacity < 1) {
            throw new IllegalArgumentException("service task executor sizing values must be positive");
        }
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                threads,
                threads,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(queueCapacity),
                daemonThreadFactory(prefix),
                new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private static ThreadFactory daemonThreadFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    @Override
    @PreDestroy
    public void close() {
        sqlExecutor.shutdownNow();
        uploadExecutor.shutdownNow();
        downloadExecutor.shutdownNow();
    }
}
