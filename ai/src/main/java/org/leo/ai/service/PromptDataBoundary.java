package org.leo.ai.service;

import java.util.regex.Pattern;

/**
 * 将外部数据放入 prompt 边界时的结构转义工具。
 *
 * <p>只转义会提前闭合边界的标签；数据正文、凭据和密钥均保持原样，
 * 便于 AI 整理报告和后续任务复用。
 */
public final class PromptDataBoundary {

    private PromptDataBoundary() {
    }

    /** 转义数据边界的结束标签，避免摘要正文提前闭合边界。 */
    public static String escapeClosingTag(String text, String tagName) {
        if (text == null || text.isEmpty()) return text;
        return text.replaceAll("(?i)</\\s*" + Pattern.quote(tagName) + "\\s*>",
                "&lt;/" + tagName + "&gt;");
    }
}
