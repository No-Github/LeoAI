package org.leo.web.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WebConfigTest {

    @Test
    void parsesAndDeduplicatesExplicitOrigins() {
        assertArrayEquals(
                new String[]{"http://localhost:3000", "https://console.example.com"},
                WebConfig.parseAllowedOrigins(
                        " http://localhost:3000,https://console.example.com,http://localhost:3000 "));
    }

    @Test
    void rejectsCredentialedWildcardOrigin() {
        assertThrows(IllegalArgumentException.class,
                () -> WebConfig.parseAllowedOrigins("*"));
    }
}
