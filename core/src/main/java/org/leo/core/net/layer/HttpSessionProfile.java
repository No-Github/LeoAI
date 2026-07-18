package org.leo.core.net.layer;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Seed 驱动且在单个节点会话内保持稳定的 HTTP 客户端画像。 */
public final class HttpSessionProfile {

    private static final String[] USER_AGENTS = {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 "
                    + "(KHTML, like Gecko) Version/18.1 Safari/605.1.15",
            "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0"
    };

    private static final String[] LANGUAGES = {
            "zh-CN,zh;q=0.9,en;q=0.8",
            "en-US,en;q=0.9",
            "en-GB,en;q=0.9,zh-CN;q=0.7"
    };

    private HttpSessionProfile() { }

    public static Map<String, String> headers(String seed, String baseUrl) {
        byte[] digest = digest((seed == null ? "" : seed) + "|http-profile");
        int profile = (digest[0] & 0xff) % USER_AGENTS.length;
        int language = (digest[1] & 0xff) % LANGUAGES.length;

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", USER_AGENTS[profile]);
        headers.put("Accept", "application/json, text/plain, */*");
        headers.put("Accept-Language", LANGUAGES[language]);
        String referer = sameOrigin(baseUrl);
        if (referer != null) headers.put("Referer", referer);
        return headers;
    }

    private static String sameOrigin(String value) {
        try {
            URI uri = URI.create(value);
            if (!uri.isAbsolute() || uri.getHost() == null) return null;
            return new URI(uri.getScheme(), uri.getUserInfo(), uri.getHost(), uri.getPort(),
                    "/", null, null).toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
