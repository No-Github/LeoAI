package org.leo.phpcore.disguise;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.leo.core.entity.Disguise;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhpBuiltinDisguiseCatalogTest {

    @Test
    void presetsRoundTripOnJavaPlatformSide() throws Exception {
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("text", "hello世界");
        sample.put("count", 7);
        sample.put("enabled", true);
        sample.put("nested", Map.of("items", List.of("one", "two")));
        sample.put("binary", new byte[]{0, 1, 2, -1});

        List<Disguise> presets = PhpBuiltinDisguiseCatalog.createPresets();
        assertEquals(List.of(PhpBuiltinDisguiseCatalog.JSON_API_ID,
                        PhpBuiltinDisguiseCatalog.FORM_SYNC_ID),
                presets.stream().map(Disguise::getDisguiseId).toList());

        for (Disguise disguise : presets) {
            assertEquals(2, disguise.getSchemaVersion());
            assertEquals(2, disguise.getProtocolVersion());
            assertTrue(disguise.supportsRuntime("php"));

            byte[] encoded = disguise.encode(sample);
            Map<String, Object> decoded = disguise.decode(encoded);

            assertEquals(sample.get("text"), decoded.get("text"));
            assertEquals(sample.get("count"), decoded.get("count"));
            assertEquals(sample.get("enabled"), decoded.get("enabled"));
            assertEquals(sample.get("nested"), decoded.get("nested"));
            assertArrayEquals((byte[]) sample.get("binary"), (byte[]) decoded.get("binary"));
        }
    }

    @Test
    void presetsUseRecognizableHttpBodyShapes() throws Exception {
        Map<String, Object> sample = Map.of("message", "ok");
        List<Disguise> presets = PhpBuiltinDisguiseCatalog.createPresets();

        Disguise json = presets.get(0);
        String jsonBody = new String(json.encode(sample), StandardCharsets.UTF_8);
        assertEquals("application/json;charset=utf-8", json.getHeaders().get("Content-Type"));
        assertTrue(jsonBody.startsWith("{"));
        assertTrue(jsonBody.contains("\"status\":\"ok\""));
        assertTrue(jsonBody.contains("\"data\":"));

        Disguise form = presets.get(1);
        String formBody = new String(form.encode(sample), StandardCharsets.UTF_8);
        assertEquals("application/x-www-form-urlencoded;charset=utf-8",
                form.getHeaders().get("Content-Type"));
        assertTrue(formBody.startsWith("action=sync&v=1&ts="));
        assertTrue(formBody.contains("&data="));
    }

    @Test
    void presetsRoundTripInPhpRuntimeWhenCliIsPresent() throws Exception {
        PhpDisguiseValidator validator = new PhpDisguiseValidator();
        for (Disguise disguise : PhpBuiltinDisguiseCatalog.createPresets()) {
            Map<String, Object> result = validator.validate(disguise);
            assertEquals(Boolean.TRUE, result.get("valid"));
            assertEquals("php", result.get("runtime"));
        }
    }

    @Test
    void rejectsAmbiguousOrMismatchedEnvelopes() {
        Disguise json = PhpBuiltinDisguiseCatalog.createPresets().get(0);
        assertThrows(IllegalArgumentException.class, () -> json.decode(
                "{\"version\":\"2.0\",\"data\":\"e30\"}".getBytes(StandardCharsets.UTF_8)));

        Disguise form = PhpBuiltinDisguiseCatalog.createPresets().get(1);
        assertThrows(IllegalArgumentException.class, () -> form.decode(
                "action=sync&v=1&data=e30&data=e30".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void javaAndPhpCodecsAreWireCompatible() throws Exception {
        String php = resolvePhpBinary();
        Assumptions.assumeTrue(php != null, "PHP CLI is not installed");

        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("text", "cross-runtime世界");
        sample.put("count", 9);
        sample.put("nested", Map.of("ok", true, "items", List.of("a", "b")));
        sample.put("binary", new byte[]{0, 10, 127, -1});

        for (Disguise disguise : PhpBuiltinDisguiseCatalog.createPresets()) {
            byte[] javaEncoded = disguise.encode(sample);
            Path script = Files.createTempFile("php-disguise-wire-", ".php");
            try {
                String source = "<?php\n" + PhpSourceSupport.wireHelpers()
                        + PhpSourceSupport.requestDecodeFunction(disguise)
                        + PhpSourceSupport.responseEncodeFunction(disguise)
                        + "$request = base64_decode($argv[1], true);\n"
                        + "if ($request === false) { fwrite(STDERR, 'invalid test input'); exit(2); }\n"
                        + "$decoded = leo_request_decode($request);\n"
                        + "if (!isset($decoded['binary']) || base64_encode($decoded['binary']) !== 'AAp//w==') { fwrite(STDERR, 'binary request mismatch'); exit(3); }\n"
                        + "$decoded['binary'] = leo_binary($decoded['binary']);\n"
                        + "$response = leo_response_encode($decoded);\n"
                        + "echo base64_encode($response);\n";
                Files.writeString(script, source, StandardCharsets.UTF_8);

                Process process = new ProcessBuilder(php, "-n", script.toString(),
                        Base64.getEncoder().encodeToString(javaEncoded))
                        .redirectErrorStream(true)
                        .start();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS));
                String output = new String(process.getInputStream().readAllBytes(),
                        StandardCharsets.UTF_8).trim();
                assertEquals(0, process.exitValue(), output);

                Map<String, Object> decoded = disguise.decode(Base64.getDecoder().decode(output));
                assertEquals(sample.get("text"), decoded.get("text"));
                assertEquals(sample.get("count"), decoded.get("count"));
                assertEquals(sample.get("nested"), decoded.get("nested"));
                assertArrayEquals((byte[]) sample.get("binary"), (byte[]) decoded.get("binary"));
            } finally {
                Files.deleteIfExists(script);
            }
        }
    }

    private String resolvePhpBinary() {
        String configured = System.getProperty("leo.php.binary");
        if (configured != null && !configured.isBlank() && Files.isExecutable(Path.of(configured))) {
            return configured;
        }
        for (String candidate : new String[]{"/opt/homebrew/bin/php", "/usr/local/bin/php", "/usr/bin/php"}) {
            if (Files.isExecutable(Path.of(candidate))) return candidate;
        }
        return null;
    }
}
