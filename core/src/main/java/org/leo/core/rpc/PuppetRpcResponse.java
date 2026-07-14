package org.leo.core.rpc;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Normalized response envelope shared by runtime adapters and upper layers. */
public record PuppetRpcResponse(
        String requestId,
        int code,
        String message,
        Object data,
        Map<String, Object> error,
        Map<String, Object> meta) {

    public PuppetRpcResponse {
        error = immutableCopy(error);
        meta = immutableCopy(meta);
    }

    public boolean isSuccess() {
        return code >= 200 && code < 300;
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> value) {
        return value == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }
}
