package org.leo.core.component;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leo.core.util.javassist.CloneWithJavassist;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanComponentLifecycleTest {

    @AfterEach
    void clearStaticState() throws Exception {
        clear(FingerprintComponent.class, "tasks");
        clear(FingerprintComponent.class, "taskLocks");
        clear(ReconScanComponent.class, "tasks");
        clear(ReconScanComponent.class, "taskLocks");
    }

    @Test
    void transformedScanPayloadsInitializeAfterMethodRandomization() throws Exception {
        assertTransformedPayload("FingerprintComponent", "Fingerprint");
        assertTransformedPayload("ReconScanComponent", "Recon");
    }

    @Test
    void fingerprintCompletionIsIdempotentAndReleasesExecutorReference() throws Exception {
        verifyCompletion(new FingerprintComponent(), FingerprintComponent.class, "fingerprint-task", true);
    }

    @Test
    void reconCompletionWorksWhenTheTaskLockHasAlreadyBeenRemoved() throws Exception {
        verifyCompletion(new ReconScanComponent(), ReconScanComponent.class, "recon-task", false);
    }

    @Test
    void explicitStopInterruptsExecutorAndPreservesTaskForPolling() throws Exception {
        FingerprintComponent component = new FingerprintComponent();
        Map tasks = state(FingerprintComponent.class, "tasks");
        Map locks = state(FingerprintComponent.class, "taskLocks");
        HashMap task = task("stop-task");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        task.put("executor", executor);
        tasks.put("stop-task", task);
        locks.put("stop-task", new Object());

        invoke(component, "stop", new Class[]{Object.class}, new Object[]{"stop-task"});

        assertTrue(executor.isShutdown());
        assertEquals("STOPPED", task.get("status"));
        assertNotNull(task.get("finishedAt"));
        assertFalse(task.containsKey("executor"));
        assertSame(task, tasks.get("stop-task"));
    }

    private void verifyCompletion(Object component, Class<?> type, String id, boolean keepLock) throws Exception {
        Map tasks = state(type, "tasks");
        Map locks = state(type, "taskLocks");
        HashMap task = task(id);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        task.put("executor", executor);
        tasks.put(id, task);
        if (keepLock) locks.put(id, new Object());

        invoke(component, "markCompleted", new Class[]{HashMap.class}, new Object[]{task});
        Object firstFinishedAt = task.get("finishedAt");
        invoke(component, "markCompleted", new Class[]{HashMap.class}, new Object[]{task});

        assertEquals("STOPPED", task.get("status"));
        assertEquals(2, ((AtomicInteger) task.get("completed")).get());
        assertNotNull(firstFinishedAt);
        assertSame(firstFinishedAt, task.get("finishedAt"));
        assertFalse(task.containsKey("executor"));
        executor.shutdownNow();
    }

    private HashMap task(String id) {
        HashMap task = new HashMap();
        task.put("taskId", id);
        task.put("status", "RUNNING");
        task.put("total", Integer.valueOf(1));
        task.put("completed", new AtomicInteger(0));
        return task;
    }

    private Object invoke(Object target, String name, Class<?>[] types, Object[] args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private void clear(Class<?> type, String name) throws Exception {
        state(type, name).clear();
    }

    private Map state(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return (Map) field.get(null);
    }

    private void assertTransformedPayload(String componentName, String suffix) throws Exception {
        String className = "org.leo.generated." + suffix + System.nanoTime();
        byte[] bytecode = CloneWithJavassist.cloneClass(componentName, className);
        Class<?> transformed = new BytecodeLoader().define(className, bytecode);
        assertTrue(Runnable.class.isAssignableFrom(transformed));
        assertTrue(transformed.getDeclaredConstructor().newInstance() instanceof Runnable);
    }

    private static final class BytecodeLoader extends ClassLoader {
        private Class<?> define(String name, byte[] bytecode) {
            return defineClass(name, bytecode, 0, bytecode.length);
        }
    }
}
