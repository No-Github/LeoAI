package org.leo.web.resources;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticAssetIntegrityTest {

    private static final Pattern LOCAL_ASSET_REFERENCE =
            Pattern.compile("(?:src|href)=\"/([^\"?#]+)(?:[?#][^\"]*)?\"");

    @Test
    void indexReferencesOnlyPackagedStaticAssets() throws IOException {
        ClassLoader classLoader = StaticAssetIntegrityTest.class.getClassLoader();
        String indexHtml;
        try (InputStream input = classLoader.getResourceAsStream("static/index.html")) {
            assertNotNull(input, "static/index.html must be packaged");
            indexHtml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        List<String> missingAssets = new ArrayList<>();
        Matcher matcher = LOCAL_ASSET_REFERENCE.matcher(indexHtml);
        while (matcher.find()) {
            String resourcePath = "static/" + matcher.group(1);
            if (classLoader.getResource(resourcePath) == null) {
                missingAssets.add("/" + matcher.group(1));
            }
        }

        assertTrue(missingAssets.isEmpty(),
                () -> "index.html references missing packaged assets: " + missingAssets);
    }
}
