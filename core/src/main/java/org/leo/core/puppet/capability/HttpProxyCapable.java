package org.leo.core.puppet.capability;

import org.leo.core.engine.socks5.Socks5ProxyStatistics;

import java.util.Map;

/**
 * Capability marker for nodes that can run an HTTP proxy.
 */
public interface HttpProxyCapable {

    Map<String, Object> startHttpProxy(int port) throws Exception;

    Map<String, Object> stopHttpProxy();

    Map<String, Object> getHttpProxyStatus();

    Socks5ProxyStatistics.StatisticsSnapshot getHttpProxyStatistics();
}
