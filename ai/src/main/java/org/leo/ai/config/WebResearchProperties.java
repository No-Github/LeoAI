package org.leo.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Agent 联网搜索与网页抓取参数。 */
@Component
@ConfigurationProperties(prefix = "leo.ai.web-research")
public class WebResearchProperties {

    private String primarySearchUrl = "https://html.duckduckgo.com/html/";
    private String fallbackSearchUrl = "https://search.brave.com/search";
    private int connectTimeoutMs = 10_000;
    private int requestTimeoutMs = 20_000;
    private int maxFetchBytes = 2 * 1024 * 1024;
    private int maxContentChars = 40_000;
    private int maxRedirects = 4;
    private String userAgent = "LeoAI-WebResearch/1.0";

    public String getPrimarySearchUrl() { return primarySearchUrl; }
    public String getFallbackSearchUrl() { return fallbackSearchUrl; }
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public int getRequestTimeoutMs() { return requestTimeoutMs; }
    public int getMaxFetchBytes() { return maxFetchBytes; }
    public int getMaxContentChars() { return maxContentChars; }
    public int getMaxRedirects() { return maxRedirects; }
    public String getUserAgent() { return userAgent; }

    public void setPrimarySearchUrl(String value) {
        primarySearchUrl = require(value, "primarySearchUrl");
    }
    public void setFallbackSearchUrl(String value) {
        fallbackSearchUrl = require(value, "fallbackSearchUrl");
    }
    public void setConnectTimeoutMs(int value) { connectTimeoutMs = Math.max(1_000, value); }
    public void setRequestTimeoutMs(int value) { requestTimeoutMs = Math.max(1_000, value); }
    public void setMaxFetchBytes(int value) { maxFetchBytes = Math.max(16_384, value); }
    public void setMaxContentChars(int value) { maxContentChars = Math.max(2_000, value); }
    public void setMaxRedirects(int value) { maxRedirects = Math.max(0, Math.min(value, 8)); }
    public void setUserAgent(String value) { userAgent = require(value, "userAgent"); }

    private String require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " 不能为空");
        return value.trim();
    }
}
