package org.leo.jmg;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 生成物使用的传输协议。
 *
 * <p>对外 API 使用规范字符串，本类型负责解析和能力判断。</p>
 */
public enum TransportProtocol {

    HTTP("http"),
    HTTP_CHUNK("httpchunk"),
    WEBSOCKET("websocket");

    private final String value;

    TransportProtocol(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public boolean isHttpFamily() {
        return this == HTTP || this == HTTP_CHUNK;
    }

    public static TransportProtocol parse(String protocol) {
        if (protocol == null || protocol.trim().isEmpty()) {
            return HTTP;
        }
        String normalized = protocol.trim().toLowerCase(Locale.ROOT);
        if ("http".equals(normalized)) {
            return HTTP;
        }
        if ("httpchunk".equals(normalized)) {
            return HTTP_CHUNK;
        }
        if ("websocket".equals(normalized)) {
            return WEBSOCKET;
        }
        throw new IllegalArgumentException(
                "传输协议必须是 http、httpchunk 或 websocket，当前值: " + protocol);
    }

    public static List<String> valuesAsStrings(TransportProtocol... protocols) {
        List<String> result = new ArrayList<String>();
        for (TransportProtocol protocol : protocols) {
            result.add(protocol.getValue());
        }
        return Collections.unmodifiableList(result);
    }
}
