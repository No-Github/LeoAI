package org.leo.core.component;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leo.core.util.javassist.CloneWithJavassist;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkProbeComponentTest {

    @AfterEach
    void clearPortScanState() throws Exception {
        Map<?, ?> tasks = state("scanTasks");
        for (Object value : tasks.values()) {
            Object executor = ((Map<?, ?>) value).get("executor");
            if (executor instanceof ExecutorService) ((ExecutorService) executor).shutdownNow();
        }
        tasks.clear();
        state("taskLocks").clear();
    }

    @Test
    void transformedProbePayloadsInitializeAfterMethodRandomization() throws Exception {
        assertTransformedRunnable("PortScanComponent");
        assertTransformedRunnable("HostIsReachableComponent");
        assertTransformedRunnable("HttpRequestComponent");
    }

    @Test
    void methodRandomizationDoesNotCollideWithInterfaceContracts() throws Exception {
        String componentId = "HttpRequestComponent";
        String className = "org.leo.generated.SeededHttpRequestComponent";
        byte[] bytecode = CloneWithJavassist.cloneClass(componentId, className, 0L);
        Class<?> transformed = new BytecodeLoader().define(className, bytecode);

        assertTrue(Runnable.class.isAssignableFrom(transformed));
        assertTrue(transformed.getDeclaredConstructor().newInstance() instanceof Runnable);
    }

    @Test
    void invalidPortIsRejectedBeforeTaskRegistration() throws Exception {
        InvocationTargetException error = assertThrows(InvocationTargetException.class,
                () -> invoke(new PortScanComponent(), params(
                        "methodName", "startScan", "scanHost", "127.0.0.1",
                        "scanPorts", new int[]{80, 70000})));
        assertTrue(error.getCause() instanceof IllegalArgumentException);
        assertTrue(state("scanTasks").isEmpty());
        assertTrue(state("taskLocks").isEmpty());
    }

    @Test
    void portQueryExposesSerializableProgressAndHidesExecutor() throws Exception {
        String taskId = "query-task";
        HashMap<String, Object> task = new HashMap<>();
        task.put("taskId", taskId);
        task.put("status", "RUNNING");
        task.put("portLength", 3);
        task.put("completedCount", new AtomicInteger(2));
        task.put("openPortList", new ArrayList<>(Arrays.asList(80)));
        task.put("executor", Executors.newSingleThreadExecutor());
        state("scanTasks").put(taskId, task);
        state("taskLocks").put(taskId, new Object());

        Map<String, Object> response = invoke(new PortScanComponent(),
                params("methodName", "queryResult", "taskId", taskId));
        Map<?, ?> snapshot = (Map<?, ?>) response.get("scanTaskInfo");

        assertEquals(2, snapshot.get("scannedCount"));
        assertEquals(2, snapshot.get("completedCount"));
        assertFalse(snapshot.containsKey("executor"));
    }

    @Test
    void stoppingPortScanShutsExecutorAndPreservesPollingState() throws Exception {
        String taskId = "stop-task";
        ExecutorService executor = Executors.newSingleThreadExecutor();
        HashMap<String, Object> task = new HashMap<>();
        task.put("taskId", taskId);
        task.put("status", "RUNNING");
        task.put("executor", executor);
        state("scanTasks").put(taskId, task);
        state("taskLocks").put(taskId, new Object());

        Map<String, Object> response = invoke(new PortScanComponent(),
                params("methodName", "stopScan", "taskId", taskId));

        assertEquals(200, code(response));
        assertTrue(executor.isShutdown());
        assertEquals("STOPPED", task.get("status"));
        assertNotNull(task.get("finishedAt"));
        assertFalse(task.containsKey("executor"));
        assertEquals(task, state("scanTasks").get(taskId));
    }

    @Test
    void hostProbeAcceptsByteHostsAndReturnsConsistentCounts() throws Exception {
        Map<String, Object> response = invoke(new HostIsReachableComponent(), params(
                "scanHosts", Arrays.asList("127.0.0.1".getBytes(StandardCharsets.UTF_8)),
                "scanTimeout", 1000));

        assertEquals(200, code(response));
        int reachable = ((Number) response.get("reachableCount")).intValue();
        int unreachable = ((Number) response.get("unreachableCount")).intValue();
        int pending = ((Number) response.get("pendingCount")).intValue();
        assertEquals(1, reachable + unreachable + pending);
        assertEquals(reachable, ((ArrayList<?>) response.get("reachableHostList")).size());
        assertEquals(unreachable, ((ArrayList<?>) response.get("unreachableHostList")).size());
    }

    @Test
    void hostProbeRejectsOversizedBatchBeforeAllocatingWorkers() {
        InvocationTargetException error = assertThrows(InvocationTargetException.class,
                () -> invoke(new HostIsReachableComponent(), params(
                        "scanHosts", Collections.nCopies(4097, "127.0.0.1"))));
        assertTrue(error.getCause() instanceof IllegalArgumentException);
    }

    @Test
    void httpRejectsMalformedUrlAndInvalidHeadersBeforeOpeningConnection() throws Exception {
        Map<String, Object> invalidUrl = invoke(new HttpRequestComponent(),
                params("method", "GET", "url", "not a url"));
        assertEquals(400, code(invalidUrl));

        Map<String, Object> invalidHeaders = invoke(new HttpRequestComponent(),
                params("method", "GET", "url", "http://127.0.0.1/", "headers", "invalid"));
        assertEquals(400, code(invalidHeaders));
    }

    @Test
    void httpProxyHandlerImplementsObjectContracts() throws Throwable {
        HttpRequestComponent handler = new HttpRequestComponent();
        Object proxy = new Object();
        Method hashCode = Object.class.getMethod("hashCode");
        Method equals = Object.class.getMethod("equals", Object.class);
        Method toString = Object.class.getMethod("toString");

        assertEquals(System.identityHashCode(proxy), handler.invoke(proxy, hashCode, null));
        assertEquals(Boolean.TRUE, handler.invoke(proxy, equals, new Object[]{proxy}));
        assertEquals(proxy.getClass().getName(), handler.invoke(proxy, toString, null));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invoke(Object component, HashMap<String, Object> params) throws Exception {
        HashMap<String, Object> results = new HashMap<>();
        setField(component, "params", params);
        setField(component, "results", results);
        component.getClass().getDeclaredMethod("invoke").invoke(component);
        return results;
    }

    private HashMap<String, Object> params(Object... values) {
        HashMap<String, Object> params = new HashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            params.put((String) values[index], values[index + 1]);
        }
        return params;
    }

    private int code(Map<String, Object> response) {
        return ((Number) response.get("code")).intValue();
    }

    @SuppressWarnings("unchecked")
    private Map<Object, Object> state(String name) throws Exception {
        Field field = PortScanComponent.class.getDeclaredField(name);
        field.setAccessible(true);
        return (Map<Object, Object>) field.get(null);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private void assertTransformedRunnable(String componentId) throws Exception {
        String className = "org.leo.generated." + componentId + System.nanoTime();
        byte[] bytecode = CloneWithJavassist.cloneClass(componentId, className);
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
