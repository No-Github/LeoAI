package org.leo.phpcore.component;

import org.leo.core.component.runtime.ComponentArtifact;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds stable per-endpoint PHP component variants without changing component behavior. */
public final class PhpComponentVariantBuilder {

    private static final Pattern DECLARED_ID = Pattern.compile("('id'\\s*=>\\s*')([^']+)(')");
    private static final Pattern HEREDOC = Pattern.compile("<<<\\s*'?([A-Za-z_][A-Za-z0-9_]*)'?");
    private static final Pattern DIAGNOSTIC_LITERAL = Pattern.compile(
            "((?:'msg'\\s*=>\\s*|throw\\s+new\\s+[A-Za-z_\\\\][A-Za-z0-9_\\\\]*\\s*\\())'([^'\\\\\\r\\n]{8,})'");
    private static final Set<String> RESERVED_VARIABLES = Set.of(
            "GLOBALS", "_SERVER", "_GET", "_POST", "_FILES", "_COOKIE", "_SESSION",
            "_REQUEST", "_ENV", "argc", "argv", "this", "php_errormsg", "http_response_header");

    private static final int DEFAULT_MAX_CACHE_ENTRIES = 1024;

    private final int maxCacheEntries;
    private final Map<String, ComponentArtifact> cache;

    public PhpComponentVariantBuilder() {
        this(DEFAULT_MAX_CACHE_ENTRIES);
    }

    PhpComponentVariantBuilder(int maxCacheEntries) {
        this.maxCacheEntries = Math.max(1, maxCacheEntries);
        this.cache = new LinkedHashMap<>(64, 0.75f, true);
    }

    public synchronized ComponentArtifact variant(ComponentArtifact base, String endpointSeed) {
        if (base == null) throw new IllegalArgumentException("base component不能为空");
        if (endpointSeed == null || endpointSeed.isBlank()) return base;
        String cacheKey = base.getDigest() + ':' + digestHex(endpointSeed).substring(0, 24);
        ComponentArtifact cached = cache.get(cacheKey);
        if (cached != null) return cached;
        ComponentArtifact created = build(base, endpointSeed);
        cache.put(cacheKey, created);
        while (cache.size() > maxCacheEntries) {
            String eldest = cache.keySet().iterator().next();
            cache.remove(eldest);
        }
        return created;
    }

    public String alias(String componentId, String endpointSeed) {
        if (endpointSeed == null || endpointSeed.isBlank()) return componentId;
        return "c" + digestHex(endpointSeed + "|component|" + componentId).substring(0, 23);
    }

    public String originalId(String reportedId, String endpointSeed, Set<String> componentIds) {
        if (reportedId == null || componentIds == null) return null;
        if (componentIds.contains(reportedId)) return reportedId;
        if (endpointSeed == null || endpointSeed.isBlank()) return null;
        for (String componentId : componentIds) {
            if (alias(componentId, endpointSeed).equals(reportedId)) return componentId;
        }
        return null;
    }

    synchronized int cachedVariantCount() {
        return cache.size();
    }

    private ComponentArtifact build(ComponentArtifact base, String endpointSeed) {
        String source = new String(base.getContent(), StandardCharsets.UTF_8);
        String alias = alias(base.getComponentId(), endpointSeed);
        Matcher declared = DECLARED_ID.matcher(source);
        if (!declared.find() || !base.getComponentId().equals(declared.group(2))) {
            throw new IllegalStateException("PHP component 声明与制品不一致: " + base.getComponentId());
        }
        source = declared.replaceFirst(Matcher.quoteReplacement(declared.group(1) + alias + declared.group(3)));
        String variantSeed = endpointSeed + '|' + base.getComponentId();
        source = rewriteVariablesAndComments(source, variantSeed);
        source = rewriteDiagnosticLiterals(source, variantSeed);
        byte[] content = source.getBytes(StandardCharsets.UTF_8);
        return new ComponentArtifact(alias, base.getVersion(), sha256(content), base.getRuntime(),
                base.getDeliveryMode(), content);
    }

    private String rewriteVariablesAndComments(String source, String seed) {
        StringBuilder output = new StringBuilder(source.length());
        int index = 0;
        while (index < source.length()) {
            if (source.startsWith("<<<", index)) {
                int end = heredocEnd(source, index);
                if (end > index) {
                    output.append(source, index, end); index = end; continue;
                }
            }
            char current = source.charAt(index);
            if (current == '\'' || current == '"') {
                index = copyQuoted(source, index, output, current, seed); continue;
            }
            if (current == '/' && index + 1 < source.length() && source.charAt(index + 1) == '*') {
                int end = source.indexOf("*/", index + 2);
                index = end < 0 ? source.length() : end + 2;
                output.append(' '); continue;
            }
            if ((current == '/' && index + 1 < source.length() && source.charAt(index + 1) == '/')
                    || current == '#') {
                int end = source.indexOf('\n', index + 1);
                if (end < 0) break;
                output.append('\n'); index = end + 1; continue;
            }
            if (current == '$' && index + 1 < source.length() && isVariableStart(source.charAt(index + 1))) {
                int end = index + 2;
                while (end < source.length() && isVariablePart(source.charAt(end))) end++;
                String name = source.substring(index + 1, end);
                output.append('$').append(variableAlias(name, seed)); index = end; continue;
            }
            output.append(current); index++;
        }
        return output.toString();
    }

    private String rewriteDiagnosticLiterals(String source, String seed) {
        Matcher matcher = DIAGNOSTIC_LITERAL.matcher(source);
        StringBuffer output = new StringBuffer(source.length());
        while (matcher.find()) {
            String replacement = matcher.group(1) + splitLiteral(matcher.group(2), seed);
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private String splitLiteral(String value, String seed) {
        if (value.length() < 8) return "'" + value + "'";
        String digest = digestHex(seed + "|literal|" + value);
        int first = 2 + Integer.parseInt(digest.substring(0, 2), 16) % Math.max(1, value.length() - 4);
        if (value.length() < 20) return quote(value.substring(0, first)) + '.' + quote(value.substring(first));
        int room = value.length() - first - 2;
        int second = first + 1 + Integer.parseInt(digest.substring(2, 4), 16) % Math.max(1, room);
        return quote(value.substring(0, first)) + '.' + quote(value.substring(first, second))
                + '.' + quote(value.substring(second));
    }

    private String quote(String value) {
        return "'" + value + "'";
    }

    private int copyQuoted(String source, int start, StringBuilder output, char quote, String seed) {
        int index = start; output.append(quote); index++;
        boolean escaped = false;
        while (index < source.length()) {
            char current = source.charAt(index);
            if (quote == '"' && !escaped && current == '$' && index + 1 < source.length()
                    && isVariableStart(source.charAt(index + 1))) {
                int end = index + 2;
                while (end < source.length() && isVariablePart(source.charAt(end))) end++;
                output.append('$').append(variableAlias(source.substring(index + 1, end), seed));
                index = end; continue;
            }
            output.append(current); index++;
            if (escaped) escaped = false;
            else if (current == '\\') escaped = true;
            else if (current == quote) break;
        }
        return index;
    }

    private int heredocEnd(String source, int start) {
        int firstLineEnd = source.indexOf('\n', start);
        if (firstLineEnd < 0) return -1;
        Matcher matcher = HEREDOC.matcher(source.substring(start, firstLineEnd));
        if (!matcher.find()) return -1;
        String marker = matcher.group(1);
        int search = firstLineEnd + 1;
        while (search < source.length()) {
            int lineEnd = source.indexOf('\n', search);
            if (lineEnd < 0) lineEnd = source.length();
            String line = source.substring(search, lineEnd).trim();
            if (line.equals(marker) || line.equals(marker + ";")) return lineEnd < source.length() ? lineEnd + 1 : lineEnd;
            search = lineEnd + 1;
        }
        return -1;
    }

    private String variableAlias(String name, String seed) {
        if (RESERVED_VARIABLES.contains(name)) return name;
        return "v" + digestHex(seed + "|variable|" + name).substring(0, 10);
    }

    private boolean isVariableStart(char value) {
        return value == '_' || Character.isLetter(value) || value >= 128;
    }

    private boolean isVariablePart(char value) {
        return isVariableStart(value) || Character.isDigit(value);
    }

    private String sha256(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder value = new StringBuilder(digest.length * 2);
            for (byte item : digest) value.append(String.format("%02x", item & 0xff));
            return value.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String digestHex(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }
}
