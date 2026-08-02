package org.leo.ai.service.workspace;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 仅支持单文件 unified diff；用于小范围、可校验的大文件编辑。 */
final class UnifiedDiffApplier {

    private static final Pattern HUNK = Pattern.compile(
            "^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$");

    private UnifiedDiffApplier() {}

    static String apply(String source, String patch) {
        if (patch == null || patch.isBlank()) {
            throw new IllegalArgumentException("patch 不能为空");
        }
        boolean trailingNewline = source.endsWith("\n");
        List<String> original = split(source);
        List<String> diff = patch.lines().toList();
        List<String> output = new ArrayList<>();
        int sourceIndex = 0;
        int index = 0;
        boolean sawHunk = false;

        while (index < diff.size()) {
            String line = diff.get(index);
            if (line.startsWith("--- ") || line.startsWith("+++ ") || line.isBlank()) {
                index++;
                continue;
            }
            Matcher matcher = HUNK.matcher(line);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("无法识别的 diff 行: " + line);
            }
            sawHunk = true;
            int oldStart = Integer.parseInt(matcher.group(1));
            int oldCount = matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : 1;
            int newCount = matcher.group(4) != null ? Integer.parseInt(matcher.group(4)) : 1;
            int targetIndex = Math.max(0, oldStart - 1);
            if (targetIndex < sourceIndex || targetIndex > original.size()) {
                throw new IllegalArgumentException("diff hunk 位置越界或重叠");
            }
            output.addAll(original.subList(sourceIndex, targetIndex));
            sourceIndex = targetIndex;
            index++;

            int consumed = 0;
            int produced = 0;
            while (index < diff.size() && !diff.get(index).startsWith("@@ ")) {
                String change = diff.get(index);
                if (change.startsWith("\\ No newline at end of file")) {
                    index++;
                    continue;
                }
                if (change.isEmpty()) {
                    throw new IllegalArgumentException("diff 内容行缺少前缀");
                }
                char marker = change.charAt(0);
                String content = change.substring(1);
                if (marker == ' ' || marker == '-') {
                    requireLine(original, sourceIndex, content);
                    if (marker == ' ') {
                        output.add(content);
                        produced++;
                    }
                    sourceIndex++;
                    consumed++;
                } else if (marker == '+') {
                    output.add(content);
                    produced++;
                } else {
                    break;
                }
                index++;
            }
            if (consumed != oldCount || produced != newCount) {
                throw new IllegalArgumentException(
                        "diff hunk 行数不一致: expected -" + oldCount + "/+" + newCount
                                + ", actual -" + consumed + "/+" + produced);
            }
        }
        if (!sawHunk) {
            throw new IllegalArgumentException("patch 不包含 unified diff hunk");
        }
        output.addAll(original.subList(sourceIndex, original.size()));
        String result = String.join("\n", output);
        return trailingNewline ? result + "\n" : result;
    }

    private static List<String> split(String text) {
        String normalized = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
        if (normalized.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(List.of(normalized.split("\n", -1)));
    }

    private static void requireLine(List<String> lines, int index, String expected) {
        if (index >= lines.size() || !lines.get(index).equals(expected)) {
            String actual = index < lines.size() ? lines.get(index) : "<EOF>";
            throw new IllegalArgumentException(
                    "patch 上下文不匹配，期望 `" + expected + "`，实际 `" + actual + "`");
        }
    }
}
