package org.leo.core.engine.socks5;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Socks5ProxyStatisticsTest {

    @Test
    void duplicateConnectionIdsDoNotInflateCounters() {
        Socks5ProxyStatistics statistics = new Socks5ProxyStatistics(1080);

        statistics.addConnection("connection-1", "first.internal", 80, "127.0.0.1");
        statistics.addConnection("connection-1", "second.internal", 443, "127.0.0.2");

        Socks5ProxyStatistics.StatisticsSnapshot snapshot = statistics.getSnapshot();
        assertEquals(1, snapshot.activeConnections);
        assertEquals(1, snapshot.totalConnections);
        assertEquals(1, snapshot.connections.size());
        assertEquals("first.internal", snapshot.connections.get(0).targetHost);
    }

    @Test
    void lateConnectionCleanupAfterResetNeverMakesActiveCountNegative() {
        Socks5ProxyStatistics statistics = new Socks5ProxyStatistics(1080);
        statistics.addConnection("connection-1", "target.internal", 80, "127.0.0.1");

        statistics.reset();
        statistics.removeConnection("connection-1");

        assertEquals(0, statistics.getSnapshot().activeConnections);
    }

    @Test
    void ignoresNonPositiveTrafficDeltas() {
        Socks5ProxyStatistics statistics = new Socks5ProxyStatistics(1080);
        statistics.addConnection("connection-1", "target.internal", 80, "127.0.0.1");

        statistics.addUploadBytes("connection-1", -5);
        statistics.addDownloadBytes("connection-1", 0);
        statistics.addUploadBytes("connection-1", 11);
        statistics.addDownloadBytes("connection-1", 17);

        Socks5ProxyStatistics.StatisticsSnapshot snapshot = statistics.getSnapshot();
        assertEquals(11, snapshot.uploadBytes);
        assertEquals(17, snapshot.downloadBytes);
        assertEquals(11, snapshot.connections.get(0).uploadBytes);
        assertEquals(17, snapshot.connections.get(0).downloadBytes);
    }
}
