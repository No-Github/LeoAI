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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhpSystemCapabilityComponentTest {

    @BeforeAll
    static void requirePhp() {
        Assumptions.assumeTrue(phpAvailable(), "PHP CLI is not installed");
    }

    @Test
    void listsAndFindsProcessesWithTheSharedResponseShape() throws Exception {
        Map<String, Object> listed = invoke("ProcessComponent.php", "list", "array()");
        assertEquals(200, code(listed));
        List<?> processes = assertInstanceOf(List.class, listed.get("processes"));
        assertFalse(processes.isEmpty());
        Map<?, ?> process = assertInstanceOf(Map.class, processes.get(0));
        assertTrue(process.containsKey("pid"));
        assertTrue(process.containsKey("name"));

        Map<String, Object> found = invoke("ProcessComponent.php", "find", "array('pid'=>getmypid())");
        assertEquals(200, code(found));
        assertTrue(((Number) found.get("total")).intValue() >= 1, found.toString());
    }

    @Test
    void collectsNetworkTopologySections() throws Exception {
        Map<String, Object> response = invoke("NetworkInfoComponent.php", "collect", "array()");
        assertEquals(200, code(response));
        Map<?, ?> network = assertInstanceOf(Map.class, response.get("networkInfo"));
        for (String key : List.of("interfaces", "arp", "routes", "dnsConfig", "hosts", "os")) {
            assertTrue(network.containsKey(key), key);
        }
        assertFalse(assertInstanceOf(List.class, network.get("interfaces")).isEmpty());
    }

    private Map<String, Object> invoke(String name, String action, String paramsExpression) throws Exception {
        URL resource = Objects.requireNonNull(getClass().getResource("/components/" + name));
        Path component = Paths.get(resource.toURI());
        String script = "$component=require $argv[1];echo json_encode(call_user_func($component['handle'],'"
                + action + "'," + paramsExpression + "));";
        Path outputFile = Files.createTempFile("php-system-component-", ".json");
        try {
            Process process = new ProcessBuilder("php", "-r", script, component.toString())
                    .redirectErrorStream(true).redirectOutput(outputFile.toFile()).start();
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), name + " timed out");
            String output = Files.readString(outputFile, StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            return PortableJsonCodec.decode(output.getBytes(StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(outputFile);
        }
    }

    private int code(Map<String, Object> response) {
        return ((Number) response.get("code")).intValue();
    }

    private static boolean phpAvailable() {
        try {
            Process process = new ProcessBuilder("php", "-v").redirectErrorStream(true).start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }
}
