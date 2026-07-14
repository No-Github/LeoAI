package org.leo.web.util;

import dev.langchain4j.model.chat.response.StreamingHandle;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AiStreamingCancellationTest {

    @Test
    void cancelsHandleCapturedAfterStopWasRequested() {
        AtomicBoolean stopRequested = new AtomicBoolean(true);
        TestHandle handle = new TestHandle();

        AiStreamingCancellation.capture(new AtomicReference<>(), handle, stopRequested::get);

        assertTrue(handle.isCancelled());
    }

    @Test
    void cancelsPreviouslyCapturedHandle() {
        AtomicReference<StreamingHandle> handleRef = new AtomicReference<>();
        TestHandle handle = new TestHandle();
        AiStreamingCancellation.capture(handleRef, handle, () -> false);

        AiStreamingCancellation.cancelCaptured(handleRef);

        assertTrue(handle.isCancelled());
    }

    private static final class TestHandle implements StreamingHandle {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        @Override
        public void cancel() {
            cancelled.set(true);
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }
    }
}
