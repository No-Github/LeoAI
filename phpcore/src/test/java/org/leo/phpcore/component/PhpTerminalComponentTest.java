package org.leo.phpcore.component;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.leo.core.util.json.PortableJsonCodec;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhpTerminalComponentTest {

    private String processId;
    private Path component;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(phpAvailable(), "PHP CLI is not installed");
        processId = "test-" + UUID.randomUUID();
        URL resource = Objects.requireNonNull(getClass().getResource("/components/ExecCommandComponent.php"));
        component = Paths.get(resource.toURI());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (processId != null && component != null) invoke("stop", "");
    }

    @Test
    void persistsVirtualTerminalAcrossPhpRequests() throws Exception {
        Map<String, Object> initialized = invoke("write", "init");
        assertEquals(Boolean.TRUE, initialized.get("initialized"));
        assertTrue(initialized.containsKey("pty"));
        assertTrue(initialized.containsKey("resizable"));
        assertTrue(initialized.containsKey("backend"));
        assertTrue(initialized.get("instanceId") instanceof String);
        assertTrue(initialized.get("backendFailures") instanceof java.util.List<?>);
        assertEquals(Boolean.FALSE, initialized.get("longPolling"));

        invoke("write", "echo terminal-ok\r");
        String firstText = readUntil("terminal-ok", 3000);
        assertTrue(firstText.contains("terminal-ok"), firstText);

        String temporaryDirectory = Paths.get(System.getProperty("java.io.tmpdir")).toRealPath().toString();
        invoke("write", "cd \"" + temporaryDirectory + "\"\r");
        invoke("write", isWindows() ? "cd\r" : "pwd\r");
        String secondText = readUntil(temporaryDirectory, 3000);
        assertTrue(secondText.contains(temporaryDirectory), secondText);

        Map<String, Object> stopped = invoke("stop", "");
        assertEquals(Boolean.FALSE, stopped.get("alive"));
        Map<String, Object> afterStop = invoke("read", "read");
        assertEquals(Boolean.FALSE, afterStop.get("alive"));
    }

    @Test
    void streamsResizesAndInterruptsPersistentPty() throws Exception {
        Map<String, Object> initialized = invoke("write", "init");
        Assumptions.assumeTrue(Boolean.TRUE.equals(initialized.get("resizable")),
                "A resizable native PTY backend is not available on this host");

        invoke("write", "LEO_MARK=native-pty; if test -t 0 && test -t 1; then echo ${LEO_MARK}-ok; fi\r");
        String nativePty = readUntil("native-pty-ok", 3000);
        assertTrue(nativePty.contains("native-pty-ok"), nativePty);

        invoke("resize", "101,33");
        invoke("write", "stty size\r");
        String resized = readUntil("33 101", 3000);
        assertTrue(resized.contains("33 101"), resized);

        invoke("write", "stty -echo; echo echo-disabled\r");
        readUntil("echo-disabled", 1500);
        invoke("read", "read");

        long started = System.nanoTime();
        invoke("write", "LEO_STREAM=pty-stream; printf ${LEO_STREAM}-start; sleep 1; printf ${LEO_STREAM}-end\r");
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 750,
                "PTY writes should not wait for command completion");
        String early = readUntil("pty-stream-start", 750);
        assertTrue(early.contains("pty-stream-start"), early);
        assertTrue(!early.contains("pty-stream-end"), early);
        String late = readUntil("pty-stream-end", 2500);
        assertTrue(late.contains("pty-stream-end"), late);

        invoke("write", "sleep 5\r");
        Thread.sleep(150);
        invoke("write", "\u0003");
        invoke("write", "echo interrupt-ok\r");
        String interrupted = readUntil("interrupt-ok", 2500);
        assertTrue(interrupted.contains("interrupt-ok"), interrupted);
    }

    @Test
    void usesCommandBackendAsTheOnlyPtyFallback() throws Exception {
        Assumptions.assumeFalse(isWindows());
        Path original = component;
        Path forcedComponent = Files.createTempFile("php-terminal-command-fallback-", ".php");
        try {
            String source = Files.readString(original, StandardCharsets.UTF_8)
                    .replace("$python = $findCommand('python3');\n    if ($python === null) $python = $findCommand('python');",
                            "$python = null;");
            Files.writeString(forcedComponent, source, StandardCharsets.UTF_8);
            component = forcedComponent;

            Map<String, Object> initialized = invoke("write", "init");
            assertEquals("unix-command", initialized.get("backend"));
            assertEquals(Boolean.FALSE, initialized.get("pty"));
            assertEquals(Boolean.FALSE, initialized.get("resizable"));

            invoke("write", "echo command-fallback-ok\r");
            assertTrue(readUntil("command-fallback-ok", 3000).contains("command-fallback-ok"));
        } finally {
            try {
                if (component != null) invoke("stop", "");
            } finally {
                processId = null;
                component = original;
                Files.deleteIfExists(forcedComponent);
            }
        }
    }

    private String readUntil(String expected, long timeoutMillis) throws Exception {
        StringBuilder output = new StringBuilder();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        do {
            Map<String, Object> response = invoke("read", "read");
            byte[] data = assertInstanceOf(byte[].class, response.get("data"));
            output.append(new String(data, StandardCharsets.UTF_8));
            if (output.indexOf(expected) >= 0) break;
            Thread.sleep(40);
        } while (System.nanoTime() < deadline);
        return output.toString();
    }

    private Map<String, Object> invoke(String action, String command) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("action", action);
        params.put("processId", processId);
        params.put("cmd", command);
        String encoded = Base64.getEncoder().encodeToString(PortableJsonCodec.encode(params));
        String script = "function leo_binary($value){return array('$leoBinary'=>base64_encode($value));}"
                + "$component=require $argv[1];"
                + "$params=json_decode(base64_decode($argv[2]),true);"
                + "echo json_encode(call_user_func($component['handle'],$params['action'],$params));";
        Process process = new ProcessBuilder("php", "-r", script, component.toString(), encoded)
                .redirectErrorStream(true)
                .start();
        assertTrue(process.waitFor(15, TimeUnit.SECONDS), "PHP terminal action timed out: " + action);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        return PortableJsonCodec.decode(output.getBytes(StandardCharsets.UTF_8));
    }

    private boolean phpAvailable() {
        try {
            Process process = new ProcessBuilder("php", "-v").redirectErrorStream(true).start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
