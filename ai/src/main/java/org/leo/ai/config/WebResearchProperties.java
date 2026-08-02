package org.leo.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Agent 联网搜索与网页抓取参数。 */
@Component
@ConfigurationProperties(prefix = "leo.ai.web-research")
public class WebResearchProperties {

    private boolean enabled = true;
    private String braveApiKey;
    private String braveSearchUrl = "https://api.search.brave.com/res/v1/web/search";
    private int connectTimeoutMs = 10_000;
    private int requestTimeoutMs = 20_000;
    private int maxFetchBytes = 2 * 1024 * 1024;
    private int maxContentChars = 40_000;
    private int maxRedirects = 4;
    private String userAgent = "LeoAI-WebResearch/1.0";

    public boolean isEnabled() { return enabled; }
    public String getBraveApiKey() { return braveApiKey; }
    public String getBraveSearchUrl() { return braveSearchUrl; }
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public int getRequestTimeoutMs() { return requestTimeoutMs; }
    public int getMaxFetchBytes() { return maxFetchBytes; }
    public int getMaxContentChars() { return maxContentChars; }
    public int getMaxRedirects() { return maxRedirects; }
    public String getUserAgent() { return userAgent; }

    public void setEnabled(boolean value) { enabled = value; }
    public void setBraveApiKey(String value) { braveApiKey = blankToNull(value); }
    public void setBraveSearchUrl(String value) { braveSearchUrl = require(value, "braveSearchUrl"); }
    public void setConnectTimeoutMs(int value) { connectTimeoutMs = Math.max(1_000, value); }
    public void setRequestTimeoutMs(int value) { requestTimeoutMs = Math.max(1_000, value); }
    public void setMaxFetchBytes(int value) { maxFetchBytes = Math.max(16_384, value); }
    public void setMaxContentChars(int value) { maxContentChars = Math.max(2_000, value); }
    public void setMaxRedirects(int value) { maxRedirects = Math.max(0, Math.min(value, 8)); }
    public void setUserAgent(String value) { userAgent = require(value, "userAgent"); }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " 不能为空");
        return value.trim();
    }
}
