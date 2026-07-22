package org.leo.core.engine.reverse;

import org.leo.core.engine.socks5.Socks5ProxyStatistics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 反向隧道：拨号 + 启动中继线程的任务。
 * 在 dialerExecutor 中执行，避免阻塞 AcceptPollLoop。
 */
class DialerTask implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(DialerTask.class);

    private final ReverseTunnelServer server;
    private final String connId;
    private final String clientAddr;

    DialerTask(ReverseTunnelServer server, String connId, String clientAddr) {
        this.server = server;
        this.connId = connId;
        this.clientAddr = clientAddr;
    }

    public void run() {
        Socket local = null;
        boolean relaysStarted = false;
        try {
            local = new Socket(server.getForwardHost(), server.getForwardPort());
            local.setTcpNoDelay(true);
            if (!server.registerLocalConnection(connId, local)) {
                return;
            }

            Socks5ProxyStatistics stats = server.getStatistics();
            if (stats != null) {
                stats.addConnection(connId, server.getForwardHost(), server.getForwardPort(),
                        clientAddr != null ? clientAddr : "");
            }

            startRelays(local);
            relaysStarted = true;
        } catch (Exception e) {
            if (server.isRunning()) {
                logger.warn("反向隧道拨号失败 {}:{} connId={}: {}",
                        server.getForwardHost(), server.getForwardPort(), connId, e.getMessage());
                closeRemote();
            } else {
                logger.debug("反向隧道已停止，取消拨号: connId={}", connId);
            }
        } finally {
            if (!relaysStarted) server.completeLocalConnection(connId, local);
        }
    }

    private void startRelays(Socket local) {
        final AtomicBoolean cleaned = new AtomicBoolean(false);
        final Runnable cleanup = new Runnable() {
            @Override
            public void run() {
                if (!cleaned.compareAndSet(false, true)) return;
                if (server.isRunning()) server.closeRemoteConnection(connId);
                server.completeLocalConnection(connId, local);
            }
        };
        Thread read = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    new ReverseReadThread(server, local, connId).run();
                } finally {
                    cleanup.run();
                }
            }
        }, "ReverseTunnel-Read-" + connId);
        Thread write = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    new ReverseWriteThread(server, local, connId).run();
                } finally {
                    cleanup.run();
                }
            }
        }, "ReverseTunnel-Write-" + connId);
        read.setDaemon(true);
        write.setDaemon(true);
        try {
            read.start();
            write.start();
        } catch (RuntimeException error) {
            cleanup.run();
            throw error;
        }
    }

    private void closeRemote() {
        server.closeRemoteConnection(connId);
    }
}
