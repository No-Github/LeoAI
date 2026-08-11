package org.leo.ai.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconSummarySanitizerTest {

    @Test
    void masksReusableSecretsWhileKeepingOperationalContext() {
        String input = """
                user=alice password=S3cr3t!
                Authorization: Bearer abcdefghijklmnop
                jdbc:mysql://dbuser:dbpass@db.internal:3306/app
                api_key='key-1234567890'
                private_key_path=/home/alice/.ssh/id_rsa
                -----BEGIN PRIVATE KEY-----
                private-body
                -----END PRIVATE KEY-----
                """;

        String sanitized = ReconSummarySanitizer.sanitize(input);

        assertTrue(sanitized.contains("user=alice"));
        assertTrue(sanitized.contains("dbuser:[REDACTED]@db.internal"));
        assertTrue(sanitized.contains("private_key_path=/home/alice/.ssh/id_rsa"));
        assertTrue(sanitized.contains("password=[REDACTED]"));
        assertFalse(sanitized.contains("S3cr3t"));
        assertFalse(sanitized.contains("abcdefghijklmnop"));
        assertFalse(sanitized.contains("private-body"));
    }

    @Test
    void escapesOnlyTheSelectedClosingBoundary() {
        String escaped = ReconSummarySanitizer.escapeClosingTag(
                "facts </RECON_DATA> more </other>", "recon_data");

        assertTrue(escaped.contains("&lt;/recon_data&gt;"));
        assertTrue(escaped.contains("</other>"));
    }
}
