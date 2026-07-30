package org.leo.ai.runtime;

import com.alibaba.fastjson.JSON;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Redacts credentials before tool arguments enter SSE events or conversation history. */
final class AiToolArgumentSanitizer {

    private static final Pattern JSON_SECRET = Pattern.compile(
            "(?i)(\"(?:password|passwd|pwd|secret|token|credential|privateKey|accessKey|apiKey)\"\\s*:\\s*\")[^\"]*(\")");
    private static final Pattern INLINE_SECRET = Pattern.compile(
            "(?i)(^|[?&;])((?:password|passwd|pwd|token|access_token|secret|api_key)=)([^&;\\s]*)");
    private static final Pattern AUTHORITY_PASSWORD = Pattern.compile("(://[^:/?#\\s]+:)[^@/?#\\s]+(@)");

    private AiToolArgumentSanitizer() {
    }

    static String sanitize(String arguments) {
        if (arguments == null || arguments.isBlank()) return arguments;
        try {
            Object parsed = JSON.parse(arguments);
            return JSON.toJSONString(sanitizeValue(null, parsed));
        } catch (Exception ignored) {
            return sanitizeText(JSON_SECRET.matcher(arguments).replaceAll("$1***$2"));
        }
    }

    private static Object sanitizeValue(String fieldName, Object value) {
        if (value == null) return null;
        if (isSecretKey(fieldName)) return "***";
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<String, Object>();
            map.forEach((key, item) -> {
                String name = String.valueOf(key);
                sanitized.put(name, sanitizeValue(name, item));
            });
            return sanitized;
        }
        if (value instanceof List<?> list) {
            List<Object> sanitized = new ArrayList<Object>(list.size());
            for (Object item : list) sanitized.add(sanitizeValue(fieldName, item));
            return sanitized;
        }
        if (value instanceof String text) return sanitizeText(text);
        return value;
    }

    private static String sanitizeText(String value) {
        String sanitized = INLINE_SECRET.matcher(value).replaceAll("$1$2***");
        return AUTHORITY_PASSWORD.matcher(sanitized).replaceAll("$1***$2");
    }

    private static boolean isSecretKey(String key) {
        String normalized = key == null ? ""
                : key.replaceAll("[^A-Za-z0-9]", "").toLowerCase();
        return normalized.contains("password")
                || normalized.contains("passwd")
                || normalized.equals("pwd")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("credential")
                || normalized.contains("privatekey")
                || normalized.contains("accesskey")
                || normalized.contains("apikey");
    }
}
