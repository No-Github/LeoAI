package org.leo.jmg.mem.packer.jsp;

import org.leo.core.util.request.GenerationRandom;

public class JspUnicoder {

    public static String encode(String content, boolean isJsp) {
        if (content == null) {
            return null;
        }
        return JspDocument.parse(content)
                .transformJavaCodeSegments(new JspDocument.ContentTransformer() {
                    @Override
                    public String transform(String javaContent) {
                        return encodeWordChars(javaContent);
                    }
                })
                .render();
    }

    private static String encodeWordChars(String input) {
        StringBuilder encoded = new StringBuilder(input.length() * 4);
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (isWordChar(ch)) {
                encoded.append(toUnicodeEscape(ch));
            } else {
                encoded.append(ch);
            }
        }
        return encoded.toString();
    }

    /**
     * 仅对 ASCII 范围（< 0x80）的字母、数字和下划线做 Unicode 转义。
     * CJK 等宽字符不在此范围内，GHOST_BITS_ENCODE 生成的汉字字面量会被安全跳过。
     */
    private static boolean isWordChar(char ch) {
        return ch < 0x80 && (Character.isLetterOrDigit(ch) || ch == '_');
    }

    /**
     * 将单个 ASCII 字符转为 Unicode 转义形式（反斜杠后跟 1-4 个字母 u，再跟 4 位十六进制）。
     * JSP 规范允许在字母 u 前使用多个重复 u（1 到 4 个均合法），编译器将其视为等价形式。
     */
    private static String toUnicodeEscape(char ch) {
        // ch 保证是 ASCII（< 0x80），直接格式化为 4 位十六进制
        int uCount = 1 + GenerationRandom.current().nextInt(4);
        String uPrefix = "\\u" + "uuu".substring(0, uCount - 1);
        return String.format("%s%04x", uPrefix, (int) ch);
    }
}
