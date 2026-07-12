package org.leo.core.session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PuppetNodeSessionContainerTest {

    @AfterEach
    void cleanUp() {
        PuppetNodeSessionContainer.clearAllSessions();
    }

    @Test
    void exposesAnImmutableSnapshotAndReportsMissingRemoval() {
        PuppetNodeSession session = new PuppetNodeSession();
        PuppetNodeSessionContainer.addSession("session-1", session);

        var snapshot = PuppetNodeSessionContainer.getAllSession();

        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.put("session-2", new PuppetNodeSession()));
        assertTrue(PuppetNodeSessionContainer.removeSession("session-1"));
        assertFalse(PuppetNodeSessionContainer.removeSession("session-1"));
    }

    @Test
    void clearClosesEverySession() {
        TrackingSession first = new TrackingSession();
        TrackingSession second = new TrackingSession();
        PuppetNodeSessionContainer.addSession("first", first);
        PuppetNodeSessionContainer.addSession("second", second);

        PuppetNodeSessionContainer.clearAllSessions();

        assertTrue(first.closed);
        assertTrue(second.closed);
    }

    private static final class TrackingSession extends PuppetNodeSession {
        private boolean closed;

        @Override
        public void close() {
            closed = true;
            super.close();
        }
    }
}
