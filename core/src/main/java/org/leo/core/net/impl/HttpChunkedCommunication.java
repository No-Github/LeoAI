package org.leo.core.net.impl;

import org.leo.core.net.Communication;
import org.leo.core.net.TransportException;
import org.leo.core.net.impl.httpchunk.Http11DuplexChannel;
import org.leo.core.util.request.RefererGenerator;
import org.leo.core.util.request.UserAgentGenerator;

import java.io.Closeable;
import java.io.IOException;
import java.net.Proxy;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** HTTP/1.1 full-duplex transport backed by one dedicated chunked connection. */
public class HttpChunkedCommunication implements Communication, Closeable {
    private final String url;
    private final String method;
    private final Map<String, String> headers;
    private final Proxy proxy;
    private final Http11DuplexChannel channel;
    private final ThreadLocal<CompletableFuture<byte[]>> splitRequest =
            new ThreadLocal<CompletableFuture<byte[]>>();

    public HttpChunkedCommunication(String url, String method, Map<String, String> headers, Proxy proxy)
            throws Exception {
        this.url = url;
        this.method = method == null || method.isEmpty() ? "POST" : method.toUpperCase(Locale.ROOT);
        this.headers = headers == null
                ? new ConcurrentHashMap<String, String>()
                : new ConcurrentHashMap<String, String>(headers);
        this.proxy = proxy == null ? Proxy.NO_PROXY : proxy;
        this.headers.putIfAbsent("User-Agent", UserAgentGenerator.generateRandomUserAgent());
        this.headers.putIfAbsent("Referer", RefererGenerator.generateRandomReferer(url));
        this.channel = new Http11DuplexChannel(url, this.method, this.headers, this.proxy);
    }

    @Override
    public byte[] sendRequest(byte[] data) throws Exception {
        return channel.sendRequest(data);
    }

    /** Compatibility split-send API; prefer {@link #sendRequest(byte[])}. */
    public void sendData(byte[] data) throws IOException {
        try {
            splitRequest.set(channel.sendAsync(data));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new TransportException(TransportException.Reason.WRITE_FAILED,
                    "HTTP duplex request write failed", e);
        }
    }

    /** Compatibility split-receive API; must follow {@link #sendData(byte[])} on the same thread. */
    public byte[] receiveData() throws IOException {
        CompletableFuture<byte[]> future = splitRequest.get();
        splitRequest.remove();
        if (future == null) {
            throw new TransportException(TransportException.Reason.FRAME_INVALID,
                    "receiveData requires sendData on the same thread");
        }
        try {
            return channel.await(future, org.leo.core.net.TransportLimits.READ_TIMEOUT_MILLIS);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new TransportException(TransportException.Reason.READ_FAILED,
                    "HTTP duplex response read failed", e);
        }
    }

    public void newConn() throws Exception {
        channel.reconnectNow();
    }

    public void heartbeat() throws Exception {
        channel.pingNow();
    }

    @Override
    public void close() {
        splitRequest.remove();
        channel.close();
    }

    public void addHeader(String key, String value) {
        if (key != null && value != null) headers.put(key, value);
    }

    public String getHeader(String key) {
        return key == null ? null : headers.get(key);
    }

    public String getUrl() { return url; }
    public String getMethod() { return method; }
    public Map<String, String> getHeaders() { return headers; }
    public Proxy getProxy() { return proxy; }
    public long getSendTime() { return channel.getLastActivityTime(); }
    public boolean isClose() { return channel.isClosed(); }
    public Http11DuplexChannel.State getState() { return channel.getState(); }
}
