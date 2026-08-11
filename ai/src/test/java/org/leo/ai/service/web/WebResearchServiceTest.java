package org.leo.ai.service.web;

import org.junit.jupiter.api.Test;
import org.leo.ai.config.WebResearchProperties;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebResearchServiceTest {

    private final WebResearchProperties properties = new WebResearchProperties();
    private final WebResearchService service = new WebResearchService(properties);

    @Test
    void defaultsToKeylessPrimaryAndFallbackSearchPages() {
        assertEquals("https://html.duckduckgo.com/html/",
                properties.getPrimarySearchUrl());
        assertEquals("https://search.brave.com/search",
                properties.getFallbackSearchUrl());
    }

    @Test
    void mapsRequestedDaysToDocumentedSearchPageBuckets() {
        assertEquals(null, service.freshness(0));
        assertEquals("d", service.freshness(1));
        assertEquals("w", service.freshness(2));
        assertEquals("w", service.freshness(7));
        assertEquals("m", service.freshness(8));
        assertEquals("y", service.freshness(365));
        assertEquals(null, service.freshness(366));
    }

    @Test
    void parsesDuckDuckGoResultsAndEnforcesDomainScope() {
        String html = """
                <div class="result">
                  <h2><a class="result__a"
                    href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fguide.docs.example.com%2Fpage%23part">
                    Scoped result</a></h2>
                  <a class="result__snippet">Useful documentation</a>
                </div>
                <div class="result">
                  <a class="result__a" href="https://outside.example.net/page">Outside</a>
                  <a class="result__snippet">Outside result</a>
                </div>
                """;

        List<String> domains = service.normalizeDomains(List.of("docs.example.com"));
        List<WebResearchService.SearchHit> results =
                service.parseDuckDuckGoResults(html, domains, 5);

        assertEquals(1, results.size());
        assertEquals("Scoped result", results.get(0).title());
        assertEquals("https://guide.docs.example.com/page", results.get(0).url());
        assertEquals("Useful documentation", results.get(0).description());
    }

    @Test
    void parsesBraveFallbackResultsWithoutApiResponseSchema() {
        String html = """
                <div class="snippet" data-type="web">
                  <a class="l1" href="https://example.com/guide">
                    <div class="search-snippet-title">Example guide</div>
                  </a>
                  <div class="generic-snippet"><div class="content">Guide summary</div></div>
                  <div class="snippet-date">2026-08-11</div>
                </div>
                """;

        List<WebResearchService.SearchHit> results =
                service.parseBraveResults(html, List.of(), 5);

        assertEquals(1, results.size());
        assertEquals("Example guide", results.get(0).title());
        assertEquals("https://example.com/guide", results.get(0).url());
        assertEquals("Guide summary", results.get(0).description());
        assertEquals("2026-08-11", results.get(0).published());
    }

    @Test
    void normalizesInternationalDomainsAndReportsInvalidValues() {
        assertEquals(List.of("xn--fsqu00a.xn--0zwm56d"),
                service.normalizeDomains(List.of("例子.测试.")));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.normalizeDomains(List.of("https://example.com/path")));
        assertTrue(error.getMessage().contains("domains"));
    }
}
