package org.leo.ai.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptDataBoundaryTest {

    @Test
    void escapesOnlyTheSelectedClosingBoundaryAndPreservesCredentials() {
        String escaped = PromptDataBoundary.escapeClosingTag(
                "password=S3cr3t! </RECON_DATA> token=abc123 </other>", "recon_data");

        assertTrue(escaped.contains("&lt;/recon_data&gt;"));
        assertTrue(escaped.contains("</other>"));
        assertTrue(escaped.contains("password=S3cr3t!"));
        assertTrue(escaped.contains("token=abc123"));
    }
}
