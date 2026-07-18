package org.leo.core.net;

import java.io.IOException;

/** Classified physical transport failure. */
public class TransportException extends IOException {
    public enum Reason {
        CONNECT_FAILED,
        WRITE_FAILED,
        READ_FAILED,
        READ_TIMEOUT,
        CONNECTION_CLOSED,
        OVERLOADED,
        MESSAGE_TOO_LARGE,
        FRAME_INVALID,
        MESSAGE_INCOMPLETE
    }

    private final Reason reason;

    public TransportException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public TransportException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
