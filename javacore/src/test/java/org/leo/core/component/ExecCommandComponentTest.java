package org.leo.core.component;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.leo.core.util.javassist.CloneWithJavassist;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecCommandComponentTest {

    @Test
    void transformedPayloadInitializesAfterMethodRandomization() throws Exception {
        String className = "org.leo.generated.Terminal" + System.nanoTime();
        byte[] bytecode = CloneWithJavassist.cloneClass("ExecCommandComponent", className);
        Class<?> transformed = new BytecodeLoader().define(className, bytecode);
        assertTrue(Runnable.class.isAssignableFrom(transformed));
        assertTrue(transformed.getDeclaredConstructor().newInstance() instanceof Runnable);
    }

    @Test
    void transformedTerminalClassesExposeDistinctRoutingInstanceIds() throws Exception {
        String firstName = "org.leo.generated.TerminalA" + System.nanoTime();
        String secondName = "org.leo.generated.TerminalB" + System.nanoTime();
        BytecodeLoader loader = new BytecodeLoader();
        Class<?> first = loader.define(firstName,
                CloneWithJavassist.cloneClass("ExecCommandComponent", firstName));
        Class<?> second = loader.define(secondName,
                CloneWithJavassist.cloneClass("ExecCommandComponent", secondName));
        assertNotEquals(routingInstanceId(first), routingInstanceId(second));
    }

    @Test
    void rejectsBackendThatExitsDuringStartupProbe() throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("windows"));
        Process process = new ProcessBuilder("/bin/sh", "-c", "exit 1").start();
        try {
            ExecCommandComponent component = new ExecCommandComponent();
            Method method = ExecCommandComponent.class.getDeclaredMethod("waitForBackendReady", Process.class);
            method.setAccessible(true);
            assertEquals(Boolean.FALSE, method.invoke(component, process));
        } finally {
            process.destroy();
        }
    }

    @Test
    void usesDependencyFreePipeAsTheOnlyUnixFallback() throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("windows"));
        ExecCommandComponent component = new ExecCommandComponent();
        Method create = ExecCommandComponent.class.getDeclaredMethod(
                "createProcessBuilder", String.class, Map.class, int.class);
        Method probe = ExecCommandComponent.class.getDeclaredMethod("waitForBackendReady", Process.class);
        create.setAccessible(true);
        probe.setAccessible(true);
        Map<String, Object> processState = new HashMap<>();
        ProcessBuilder builder = (ProcessBuilder) create.invoke(component, "pipe-test", processState, 1);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        try {
            assertEquals(Boolean.TRUE, probe.invoke(component, process));
            assertEquals("unix-pipe", processState.get("backend"));
            assertEquals(Boolean.FALSE, processState.get("pty"));
            assertEquals(Boolean.FALSE, processState.get("resizable"));
            process.getOutputStream().write(
                    "JAVA_PIPE_A=java-pipe; JAVA_PIPE_B=fallback-ok; echo ${JAVA_PIPE_A}-${JAVA_PIPE_B}\n"
                            .getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().flush();

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            InputStream input = process.getInputStream();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (System.nanoTime() < deadline && output.toString("UTF-8").indexOf("java-pipe-fallback-ok") < 0) {
                while (input.available() > 0) output.write(input.read());
                Thread.sleep(25L);
            }
            assertTrue(output.toString("UTF-8").contains("java-pipe-fallback-ok"), output.toString("UTF-8"));
        } finally {
            process.destroy();
        }
    }

    @Test
    void prefersConfiguredInteractiveShellBeforeCompatibilityFallbacks() throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("windows"));
        ExecCommandComponent component = new ExecCommandComponent();
        Method select = ExecCommandComponent.class.getDeclaredMethod("selectShell");
        select.setAccessible(true);

        String selected = (String) select.invoke(component);
        String configured = System.getenv("SHELL");
        if (configured != null) {
            java.io.File configuredFile = new java.io.File(configured);
            if (configuredFile.isFile() && configuredFile.canExecute()) {
                assertEquals(configuredFile.getAbsolutePath(), selected);
                return;
            }
        }
        for (String candidate : new String[]{"/bin/bash", "/bin/zsh", "/bin/ksh", "/bin/sh"}) {
            java.io.File file = new java.io.File(candidate);
            if (file.isFile() && file.canExecute()) {
                assertEquals(file.getAbsolutePath(), selected);
                return;
            }
        }
        assertEquals("/bin/sh", selected);
    }

    @Test
    void selectsConfiguredWindowsCommandProcessorWithPortableFallback() throws Exception {
        ExecCommandComponent component = new ExecCommandComponent();
        Method select = ExecCommandComponent.class.getDeclaredMethod("selectWindowsShell");
        select.setAccessible(true);

        String selected = (String) select.invoke(component);
        String configured = System.getenv("ComSpec");
        if (configured != null && new java.io.File(configured).isFile()) {
            assertEquals(new java.io.File(configured).getAbsolutePath(), selected);
        } else {
            assertTrue(selected.toLowerCase().endsWith("cmd.exe"), selected);
        }
    }

    @Test
    void longPollWaitReturnsAsSoonAsOutputArrives() throws Exception {
        ExecCommandComponent component = new ExecCommandComponent();
        Method wait = ExecCommandComponent.class.getDeclaredMethod(
                "waitForReadableOutput", Map.class, int.class);
        wait.setAccessible(true);
        Map<String, Object> processState = new ConcurrentHashMap<>();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        processState.put("output", output);

        Thread producer = new Thread(() -> {
            try {
                Thread.sleep(100L);
                synchronized (output) {
                    output.write('x');
                }
                synchronized (processState) {
                    processState.notifyAll();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        producer.start();
        long started = System.nanoTime();
        wait.invoke(component, processState, 1000);
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        producer.join(1000L);

        assertTrue(elapsed >= 50L, "long poll should wait for output");
        assertTrue(elapsed < 750L, "long poll should wake before its deadline");
        assertEquals(1, output.size());
    }

    @Test
    void firstWriteStartsProcessAndIsNotDropped() throws Exception {
        String processId = "test-" + UUID.randomUUID();
        try {
            Map<String, Object> started = invoke(processId, 0, "echo java-first-write-ok\r");
            assertEquals(200, ((Number) started.get("code")).intValue());
            assertEquals(Boolean.TRUE, started.get("alive"));

            String output = readUntil(processId, "java-first-write-ok", 3000);
            assertTrue(output.contains("java-first-write-ok"), output);
        } finally {
            invoke(processId, 2, "");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedStartupRemovesProcessPlaceholder() throws Exception {
        String processId = "failed-" + UUID.randomUUID();
        Field envField = ExecCommandComponent.class.getDeclaredField("env");
        envField.setAccessible(true);
        Map<String, Map<String, Object>> environment =
                (Map<String, Map<String, Object>>) envField.get(null);
        Map<String, Object> failed = new HashMap<>();
        failed.put("exited", Boolean.TRUE);
        failed.put("error", "startup failed");
        failed.put("lastAccessTime", System.currentTimeMillis());
        environment.put(processId, failed);
        try {
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> invoke(processId, 0, "init"));
            assertEquals("startup failed", error.getMessage());
            assertFalse(environment.containsKey(processId));
        } finally {
            environment.remove(processId);
        }
    }

    @Test
    void startsReadyStreamsResizesAndInterruptsPty() throws Exception {
        String processId = "test-" + UUID.randomUUID();
        try {
            Map<String, Object> initialized = invoke(processId, 0, "init");
            assertEquals(200, ((Number) initialized.get("code")).intValue());
            assertEquals(Boolean.TRUE, initialized.get("initialized"));
            assertEquals(Boolean.TRUE, initialized.get("alive"));
            assertTrue(initialized.get("backend") instanceof String);
            assertTrue(initialized.get("instanceId") instanceof String);
            assertEquals(Boolean.TRUE, initialized.get("longPolling"));

            Assumptions.assumeTrue(Boolean.TRUE.equals(initialized.get("pty")),
                    "A native PTY backend is not available on this host");
            assertEquals(Boolean.TRUE, initialized.get("resizable"));

            invoke(processId, 0,
                    "LEO_MARK=native-java-pty; if test -t 0 && test -t 1; then echo ${LEO_MARK}-ok; fi\r");
            String nativePty = readUntil(processId, "native-java-pty-ok", 3000);
            assertTrue(nativePty.contains("native-java-pty-ok"), nativePty);

            Map<String, Object> resized = invoke(processId, 3, "101,33");
            assertEquals(Boolean.TRUE, resized.get("resized"));
            invoke(processId, 0, "stty size\r");
            String sizeOutput = readUntil(processId, "33 101", 3000);
            assertTrue(sizeOutput.contains("33 101"), sizeOutput);

            invoke(processId, 0, "stty -echo; echo java-echo-disabled\r");
            readUntil(processId, "java-echo-disabled", 1500);
            invoke(processId, 1, "");

            long started = System.nanoTime();
            invoke(processId, 0,
                    "LEO_STREAM=java-pty-stream; printf ${LEO_STREAM}-start; sleep 1; printf ${LEO_STREAM}-end\r");
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 750,
                    "PTY writes should return before command completion");
            String early = readUntil(processId, "java-pty-stream-start", 750);
            assertTrue(early.contains("java-pty-stream-start"), early);
            assertFalse(early.contains("java-pty-stream-end"), early);
            String late = readUntil(processId, "java-pty-stream-end", 2500);
            assertTrue(late.contains("java-pty-stream-end"), late);

            invoke(processId, 0, "sleep 5\r");
            Thread.sleep(150);
            invoke(processId, 0, "\u0003");
            invoke(processId, 0, "echo java-interrupt-ok\r");
            String interrupted = readUntil(processId, "java-interrupt-ok", 2500);
            assertTrue(interrupted.contains("java-interrupt-ok"), interrupted);
        } finally {
            invoke(processId, 2, "");
        }
    }

    private String readUntil(String processId, String expected, long timeoutMillis) throws Exception {
        StringBuilder output = new StringBuilder();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        do {
            Map<String, Object> response = invoke(processId, 1, "");
            Object raw = response.get("data");
            if (raw instanceof byte[]) output.append(new String((byte[]) raw, StandardCharsets.UTF_8));
            if (output.indexOf(expected) >= 0) break;
            Thread.sleep(40);
        } while (System.nanoTime() < deadline);
        return output.toString();
    }

    private String routingInstanceId(Class<?> type) throws Exception {
        for (Field field : type.getDeclaredFields()) {
            if (field.getType() != String.class
                    || !java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            Object value = field.get(null);
            if (value instanceof String text && text.matches("[0-9a-f]+-[0-9a-f]+")) return text;
        }
        throw new AssertionError("routing instance id field was not found");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invoke(String processId, int operation, String command) throws Exception {
        ExecCommandComponent component = new ExecCommandComponent();
        HashMap<String, Object> params = new HashMap<>();
        params.put("processId", processId.getBytes(StandardCharsets.UTF_8));
        params.put("op", operation);
        params.put("cmd", command.getBytes(StandardCharsets.UTF_8));
        HashMap<String, Object> results = new HashMap<>();

        Field paramsField = ExecCommandComponent.class.getDeclaredField("params");
        Field resultsField = ExecCommandComponent.class.getDeclaredField("results");
        paramsField.setAccessible(true);
        resultsField.setAccessible(true);
        paramsField.set(component, params);
        resultsField.set(component, results);

        Method method = ExecCommandComponent.class.getDeclaredMethod("execCommand");
        method.setAccessible(true);
        try {
            method.invoke(component);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw error;
        }
        return (Map<String, Object>) resultsField.get(component);
    }

    private static final class BytecodeLoader extends ClassLoader {
        private Class<?> define(String name, byte[] bytecode) {
            return defineClass(name, bytecode, 0, bytecode.length);
        }
    }
}
