package org.leo.core.rpc;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Versioned request envelope shared by all Puppet runtime modules. */
public record PuppetRpcRequest(
        int protocolVersion,
        String requestId,
        PuppetOperation operation,
        String hostId,
        long timestamp,
        ComponentReference component,
        String action,
        Map<String, Object> params) {

    public static final int CURRENT_PROTOCOL_VERSION = 2;

    public PuppetRpcRequest {
        if (protocolVersion <= 0) {
            throw new IllegalArgumentException("protocolVersion必须大于0");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId不能为空");
        }
        if (operation == null) {
            throw new IllegalArgumentException("operation不能为空");
        }
        requestId = requestId.trim();
        params = params == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }
}
