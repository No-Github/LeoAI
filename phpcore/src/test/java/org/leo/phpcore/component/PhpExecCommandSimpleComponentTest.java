package org.leo.phpcore.component;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.leo.core.util.json.PortableJsonCodec;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhpExecCommandSimpleComponentTest {

    private static Path component;

    @BeforeAll
    static void setUp() throws Exception {
        Assumptions.assumeTrue(phpAvailable(), "PHP CLI is not installed");
        URL resource = Objects.requireNonNull(
                PhpExecCommandSimpleComponentTest.class.getResource("/components/ExecCommandSimpleComponent.php"));
        component = Paths.get(resource.toURI());
    }

    @Test
    void executesWithBoundedPrimaryBackend() throws Exception {
        Map<String, Object> result = invoke(component);
        assertEquals("simple-ok-err", result.get("output"));
        assertEquals(7, ((Number) result.get("exitCode")).intValue());
    }

    @Test
    void usesExecAsTheOnlyFallback() throws Exception {
        String source = Files.readString(component, StandardCharsets.UTF_8)
                .replace("if ($available('proc_open')) {", "if (false) {");
        Path forced = Files.createTempFile("php-command-simple-fallback-", ".php");
        try {
            Files.writeString(forced, source, StandardCharsets.UTF_8);
            Map<String, Object> result = invoke(forced);
            assertEquals("simple-ok-err", result.get("output"));
            assertEquals(7, ((Number) result.get("exitCode")).intValue());
        } finally {
            Files.deleteIfExists(forced);
        }
    }

    private static Map<String, Object> invoke(Path source) throws Exception {
        String script = "$component=require $argv[1];"
                + "$code=\"fwrite(STDOUT,'simple-ok');fwrite(STDERR,'-err');exit(7);\";"
                + "$cmd=escapeshellarg(PHP_BINARY).' -r '.escapeshellarg($code);"
                + "echo json_encode(call_user_func($component['handle'],'exec',array('cmd'=>$cmd)));";
        Process process = new ProcessBuilder("php", "-r", script, source.toString())
                .redirectErrorStream(true)
                .start();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "PHP simple command timed out");
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        return PortableJsonCodec.decode(output.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean phpAvailable() {
        try {
            Process process = new ProcessBuilder("php", "-r",
                    "exit(function_exists('proc_open')&&function_exists('exec')?0:1);")
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }
}
