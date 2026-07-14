package org.leo.web.util;

import dev.langchain4j.model.chat.response.StreamingHandle;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Resolves the race between a user stop request and late streaming-handle delivery. */
public final class AiStreamingCancellation {

    private AiStreamingCancellation() {
    }

    public static void capture(AtomicReference<StreamingHandle> handleRef,
                               StreamingHandle candidate,
                               BooleanSupplier stopRequested) {
        if (candidate == null) return;
        handleRef.compareAndSet(null, candidate);
        StreamingHandle handle = handleRef.get();
        if (handle != null && stopRequested.getAsBoolean()) {
            handle.cancel();
        }
    }

    public static void cancelCaptured(AtomicReference<StreamingHandle> handleRef) {
        StreamingHandle handle = handleRef.get();
        if (handle != null) {
            handle.cancel();
        }
    }
}
