package org.leo.core.net;

/** Shared byte and timeout limits for every physical transport. */
public final class TransportLimits {
    public static final int MAX_MESSAGE_BYTES = 16 * 1024 * 1024;
    public static final int MAX_FRAME_BYTES = 64 * 1024;
    public static final int WEBSOCKET_FRAME_HEADER_BYTES = 1 + Long.BYTES + Integer.BYTES * 3;
    public static final int MAX_FRAGMENT_PAYLOAD_BYTES =
            MAX_FRAME_BYTES - WEBSOCKET_FRAME_HEADER_BYTES;
    public static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    public static final int READ_TIMEOUT_MILLIS = 30_000;
    public static final int WRITE_TIMEOUT_MILLIS = 30_000;

    private TransportLimits() {
    }

    public static void requireMessageSize(byte[] data) throws TransportException {
        int length = data == null ? 0 : data.length;
        if (length > MAX_MESSAGE_BYTES) {
            throw new TransportException(TransportException.Reason.MESSAGE_TOO_LARGE,
                    "消息超过限制: " + length + " > " + MAX_MESSAGE_BYTES);
        }
    }
}
