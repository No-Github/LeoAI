package org.leo.web.util;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiSseExecutorTest {

    @Test
    void boundsChatConcurrencyAndQueue() throws Exception {
        try (AiSseExecutor executor = new AiSseExecutor(1, 1, 1)) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            executor.submitChat(() -> await(started, release));
            assertTrue(started.await(1, TimeUnit.SECONDS));

            executor.submitChat(() -> { });

            assertEquals(1, executor.activeChatTasks());
            assertEquals(1, executor.queuedChatTasks());
            assertThrows(RejectedExecutionException.class,
                    () -> executor.submitChat(() -> { }));
            release.countDown();
        }
    }

    @Test
    void drainTasksUseDirectHandoffAndDaemonThreads() throws Exception {
        try (AiSseExecutor executor = new AiSseExecutor(1, 1, 1)) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicBoolean daemon = new AtomicBoolean(false);
            AtomicReference<String> threadName = new AtomicReference<>();

            executor.submitDrain(() -> {
                daemon.set(Thread.currentThread().isDaemon());
                threadName.set(Thread.currentThread().getName());
                await(started, release);
            });
            assertTrue(started.await(1, TimeUnit.SECONDS));

            assertEquals(1, executor.activeDrainTasks());
            assertThrows(RejectedExecutionException.class,
                    () -> executor.submitDrain(() -> { }));
            assertTrue(daemon.get());
            assertTrue(threadName.get().startsWith("ai-sse-drain-"));
            release.countDown();
        }
    }

    private static void await(CountDownLatch started, CountDownLatch release) {
        started.countDown();
        try {
            release.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
