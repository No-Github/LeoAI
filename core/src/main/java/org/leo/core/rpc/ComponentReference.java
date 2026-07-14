package org.leo.core.rpc;

/** Stable component identity carried by the runtime-neutral RPC envelope. */
public record ComponentReference(String id, String version, String digest) {

    public ComponentReference {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("component id不能为空");
        }
        id = id.trim();
    }
}
