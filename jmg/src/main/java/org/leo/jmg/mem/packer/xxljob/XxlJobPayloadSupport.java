package org.leo.jmg.mem.packer.xxljob;

import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.PackerResources;

final class XxlJobPayloadSupport {
    private static final String TEMPLATE = PackerResources.loadTemplate(
            "/xxl-job/XXL-Job-DefineClass.java");

    private XxlJobPayloadSupport() {
    }

    static String source(ClassPackerConfig config) {
        return TEMPLATE.replace("{{className}}", config.getClassName())
                .replace("{{base64Str}}", config.getClassBytesBase64Str());
    }

    static String jsonEscape(String value) {
        StringBuilder result = new StringBuilder(value.length() + 32);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': result.append("\\\""); break;
                case '\\': result.append("\\\\"); break;
                case '\b': result.append("\\b"); break;
                case '\f': result.append("\\f"); break;
                case '\n': result.append("\\n"); break;
                case '\r': result.append("\\r"); break;
                case '\t': result.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        String hex = Integer.toHexString(c);
                        result.append("\\u");
                        for (int j = hex.length(); j < 4; j++) result.append('0');
                        result.append(hex);
                    } else {
                        result.append(c);
                    }
            }
        }
        return result.toString();
    }
}
