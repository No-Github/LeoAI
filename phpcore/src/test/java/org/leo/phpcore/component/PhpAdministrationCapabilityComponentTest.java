package org.leo.phpcore.component;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhpAdministrationCapabilityComponentTest {

    @BeforeAll
    static void requirePhp() {
        Assumptions.assumeTrue(phpAvailable(), "PHP CLI is not installed");
    }

    @Test
    void queriesAndAggregatesFileEventLogs(@TempDir Path directory) throws Exception {
        Path log = directory.resolve("access.log");
        Files.writeString(log,
                "10.0.0.1 - - [18/Jul/2026:10:00:00 +0800] \"GET /ok HTTP/1.1\" 200 12 \"-\" \"curl\"\n"
                        + "10.0.0.2 - - [18/Jul/2026:10:01:00 +0800] \"POST /bad HTTP/1.1\" 503 9 \"-\" \"agent\"\n",
                StandardCharsets.UTF_8);
        String source = phpString(log.toString());

        Map<String, Object> queried = invoke("EventLogComponent.php", "query",
                "array('source'=>" + source + ",'maxEntries'=>20,'minStatus'=>500)");
        assertEquals(200, code(queried));
        List<?> entries = assertInstanceOf(List.class, queried.get("entries"));
        assertEquals(1, entries.size());
        assertEquals(503, ((Number) assertInstanceOf(Map.class, entries.get(0)).get("status")).intValue());
        assertTrue(assertInstanceOf(Map.class, queried.get("meta")).containsKey("endByte"));

        Map<String, Object> metadata = invoke("EventLogComponent.php", "meta",
                "array('source'=>" + source + ",'lines'=>2,'fromTail'=>true)");
        assertEquals(200, code(metadata));
        assertEquals(2, assertInstanceOf(List.class, metadata.get("lines")).size());

        Map<String, Object> aggregate = invoke("EventLogComponent.php", "aggregate",
                "array('source'=>" + source + ",'groupBy'=>'status','topN'=>5,'maxScan'=>100)");
        assertEquals(200, code(aggregate));
        assertEquals(2, ((Number) aggregate.get("unique")).intValue());
        assertInstanceOf(List.class, aggregate.get("groups"));
    }

    @Test
    void listsAccountsAndReportsCurrentIdentity() throws Exception {
        Map<String, Object> users = invoke("UserAccountComponent.php", "listUsers", "array()");
        assertEquals(200, code(users));
        Map<?, ?> userData = assertInstanceOf(Map.class, users.get("data"));
        assertInstanceOf(List.class, userData.get("users"));

        Map<String, Object> groups = invoke("UserAccountComponent.php", "listGroups", "array()");
        assertEquals(200, code(groups));
        assertInstanceOf(List.class, assertInstanceOf(Map.class, groups.get("data")).get("groups"));

        Map<String, Object> identity = invoke("UserAccountComponent.php", "whoami", "array()");
        assertEquals(200, code(identity));
        assertInstanceOf(Map.class, assertInstanceOf(Map.class, identity.get("data")).get("detail"));
    }

    @Test
    void inspectsFirewallAndKeepsRegistryResponsePortable() throws Exception {
        Map<String, Object> firewall = invoke("FirewallComponent.php", "status", "array()");
        assertEquals(200, code(firewall));
        Map<?, ?> detail = assertInstanceOf(Map.class,
                assertInstanceOf(Map.class, firewall.get("data")).get("detail"));
        assertTrue(detail.containsKey("tool"));

        Map<String, Object> registry = invoke("RegistryComponent.php", "query",
                "array('keyPath'=>'HKCU\\\\Software','recursive'=>false)");
        assertTrue(code(registry) == 200 || code(registry) == 400);
    }

    private Map<String, Object> invoke(String name, String action, String paramsExpression) throws Exception {
        URL resource = Objects.requireNonNull(getClass().getResource("/components/" + name));
        Path component = Paths.get(resource.toURI());
        String script = "$component=require $argv[1];echo json_encode(call_user_func($component['handle'],'"
                + action + "'," + paramsExpression + "));";
        Path outputFile = Files.createTempFile("php-admin-component-", ".json");
        try {
            Process process = new ProcessBuilder("php", "-r", script, component.toString())
                    .redirectErrorStream(true).redirectOutput(outputFile.toFile()).start();
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), name + " timed out");
            String output = Files.readString(outputFile, StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            return PortableJsonCodec.decode(output.getBytes(StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(outputFile);
        }
    }

    private String phpString(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
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
