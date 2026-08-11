package org.leo.ai.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 侦察摘要进入持久化、压缩模型或 system prompt 前的统一凭据脱敏器。
 *
 * <p>摘要应保留凭据类型、账号、主机和来源位置等可检索事实，但不保存可直接复用的
 * 密码、令牌、私钥正文或 URI 用户信息中的口令。
 */
public final class ReconSummarySanitizer {

    static final String MASK = "[REDACTED]";

    private static final Pattern PRIVATE_KEY = Pattern.compile(
            "(?is)-----BEGIN(?: [A-Z0-9]+)? PRIVATE KEY-----.*?"
                    + "-----END(?: [A-Z0-9]+)? PRIVATE KEY-----");
    private static final Pattern BEARER_TOKEN = Pattern.compile(
            "(?i)(\\b(?:authorization\\s*:\\s*)?bearer\\s+)([A-Za-z0-9._~+/=-]{8,})");
    private static final Pattern COMMAND_SECRET = Pattern.compile(
            "(?i)(--?(?:password|passwd|pwd|token|api[-_]?key|access[-_]?key|secret[-_]?key)"
                    + "(?:=|\\s+))([^\\s,;]+)");
    private static final Pattern LABELED_SECRET = Pattern.compile(
            "(?i)((?:[\"']?(?:password|passwd|pwd|token|api[-_ ]?key|access[-_ ]?key|"
                    + "secret[-_ ]?key|client[-_ ]?secret)[\"']?)"
                    + "\\s*[:=]\\s*[\"']?)([^\\s\"',;}(&]+)");
    private static final Pattern URI_USER_INFO = Pattern.compile(
            "(?i)(\\b[a-z][a-z0-9+.-]*://[^\\s/:@]+:)([^\\s/@]+)(@)");

    private ReconSummarySanitizer() {
    }

    public static String sanitize(String text) {
        if (text == null || text.isEmpty()) return text;
        String sanitized = PRIVATE_KEY.matcher(text).replaceAll(MASK);
        sanitized = replaceSecretGroup(sanitized, BEARER_TOKEN, 2);
        sanitized = replaceSecretGroup(sanitized, COMMAND_SECRET, 2);
        sanitized = replaceSecretGroup(sanitized, LABELED_SECRET, 2);
        sanitized = replaceSecretGroup(sanitized, URI_USER_INFO, 2);
        return sanitized;
    }

    /** 转义数据边界的结束标签，避免摘要正文提前闭合边界。 */
    public static String escapeClosingTag(String text, String tagName) {
        if (text == null || text.isEmpty()) return text;
        return text.replaceAll("(?i)</\\s*" + Pattern.quote(tagName) + "\\s*>",
                "&lt;/" + tagName + "&gt;");
    }

    private static String replaceSecretGroup(String input, Pattern pattern, int secretGroup) {
        Matcher matcher = pattern.matcher(input);
        StringBuffer output = new StringBuffer(input.length());
        while (matcher.find()) {
            String match = matcher.group();
            int relativeStart = matcher.start(secretGroup) - matcher.start();
            int relativeEnd = matcher.end(secretGroup) - matcher.start();
            String replacement = match.substring(0, relativeStart)
                    + MASK + match.substring(relativeEnd);
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(output);
        return output.toString();
    }
}
