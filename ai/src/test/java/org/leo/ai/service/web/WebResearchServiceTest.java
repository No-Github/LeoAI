package org.leo.ai.service.web;

import org.junit.jupiter.api.Test;
import org.leo.ai.agent.AiToolException;
import org.leo.ai.config.WebResearchProperties;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebResearchServiceTest {

    @Test
    void reportsMissingSearchKeyAsModelCorrectableToolError() {
        WebResearchService service = new WebResearchService(new WebResearchProperties());

        AiToolException error = assertThrows(AiToolException.class,
                () -> service.search("测试查询", List.of(), 0, 8));

        assertEquals("WEB_SEARCH_NOT_CONFIGURED", error.code());
        assertEquals(AiToolException.Recovery.MODEL, error.recovery());
        assertTrue(error.safeMessage().contains("LEO_AI_WEB_SEARCH_API_KEY"));
        assertTrue(error.hint().contains("webFetch"));
    }

    @Test
    void reportsDisabledResearchAsModelCorrectableWithoutStartingARequest() {
        WebResearchProperties properties = new WebResearchProperties();
        properties.setEnabled(false);
        WebResearchService service = new WebResearchService(properties);

        AiToolException error = assertThrows(AiToolException.class,
                () -> service.fetch("https://example.com", 2_000));

        assertEquals("WEB_RESEARCH_DISABLED", error.code());
        assertEquals(AiToolException.Recovery.MODEL, error.recovery());
        assertTrue(error.hint().contains("webSearch/webFetch"));
    }
}
