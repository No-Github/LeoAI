package org.leo.core.rpc;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Minimal execution envelope shared by all Puppet runtime modules. */
public record PuppetRpcRequest(
        String requestId,
        PuppetOperation operation,
        String hostId,
        String component,
        String action,
        Map<String, Object> params) {

    public PuppetRpcRequest {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId不能为空");
        }
        if (operation == null) {
            throw new IllegalArgumentException("operation不能为空");
        }
        requestId = requestId.trim();
        hostId = trimToNull(hostId);
        component = trimToNull(component);
        action = trimToNull(action);
        params = params == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
