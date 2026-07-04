package org.leo.service.puppetnode;

import org.leo.core.entity.Disguise;
import org.leo.core.entity.Puppet;
import org.leo.core.entity.User;
import org.leo.core.manager.DisguiseManager;
import org.leo.core.net.Communication;
import org.leo.core.net.impl.HttpChunkedCommunication;
import org.leo.core.net.impl.HttpCommunication;
import org.leo.core.net.impl.WebSocketCommunication;
import org.leo.core.net.layer.HeaderNoiseStrategy;
import org.leo.core.net.layer.PaddingStrategy;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;
import org.leo.core.net.layer.TlsFingerprintStrategy;
import org.leo.core.net.layer.UrlStrategy;
import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.core.puppet.impl.JavaPuppetNode;
import org.leo.core.util.json.JsonUtil;
import org.leo.service.PuppetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runtime factory for Puppet nodes.
 *
 * <p>The transport protocol (http/websocket/httpChunked) is independent from the
 * node runtime type. Keeping creation here prevents callers from
 * baking {@link JavaPuppetNode} construction details into lifecycle code.
 */
@Service
public class PuppetNodeFactory {

    private static final Logger logger = LoggerFactory.getLogger(PuppetNodeFactory.class);
    private static final int MAX_PARENT_DEPTH = 100;
    private static final int PROXY_ENABLED = 1;
    private static final String TYPE_JAVA = "java";

    private final PuppetService puppetService;

    public PuppetNodeFactory(PuppetService puppetService) {
        this.puppetService = puppetService;
    }

    public AbstractPuppetNode createLiveNode(Puppet puppet, User user) throws Exception {
        if (puppet == null) {
            throw new IllegalArgumentException("Puppet不能为空");
        }

        String type = normalizeType(puppet.getType());
        if (TYPE_JAVA.equals(type)) {
            return createJavaNode(puppet, user);
        }

        throw new IllegalArgumentException("暂不支持的 Puppet 类型: " + type + "，当前支持: java");
    }

    public JavaPuppetNode createJavaNode(Puppet puppet, User user) throws Exception {
        Puppet transportPuppet = resolveTransportPuppet(puppet);
        Communication communication = getCommunication(transportPuppet, getProxy(transportPuppet));
        if (communication == null) {
            throw new IllegalArgumentException("无法创建通信连接，协议不支持: " + transportPuppet.getProtocol());
        }

        applyTlsFingerprintStrategy(puppet, communication);

        JavaPuppetNode node = new JavaPuppetNode();
        node.setPuppet(puppet);
        node.setUser(user);
        buildRequestAndResponseChain(puppet, node);
        node.setCommunication(communication);
        if (puppet.getMaxReqCount() != null && puppet.getMaxReqCount() > 0) {
            node.setMaxReqCount(puppet.getMaxReqCount());
        }
        node.initService();
        applyUrlStrategy(puppet, node);
        applyPaddingStrategy(puppet, node);
        applyHeaderNoiseStrategy(puppet, node);
        return node;
    }

    public Puppet resolveTransportPuppet(Puppet puppet) {
        Puppet current = puppet;
        int depth = 0;
        while (current != null && depth < MAX_PARENT_DEPTH) {
            String parentId = current.getParentPuppetId();
            if (parentId == null || parentId.isBlank() || "root".equals(parentId)) {
                return current;
            }
            current = puppetService.findPuppetById(parentId);
            depth++;
        }
        return puppet;
    }

    public Proxy getProxy(Puppet puppet) {
        if (puppet == null) return Proxy.NO_PROXY;
        Integer proxyEnabled = puppet.getProxyEnabled();
        if (proxyEnabled == null || proxyEnabled != PROXY_ENABLED) return Proxy.NO_PROXY;

        String proxyHost = puppet.getProxyHost();
        Integer proxyPort = puppet.getProxyPort();
        if (proxyHost == null || proxyPort == null) return Proxy.NO_PROXY;

        String proxyType = puppet.getProxyType();
        Proxy.Type type = Proxy.Type.DIRECT;
        if ("http".equals(proxyType)) type = Proxy.Type.HTTP;
        else if ("socks".equals(proxyType)) type = Proxy.Type.SOCKS;

        return new Proxy(type, new InetSocketAddress(proxyHost, proxyPort));
    }

    public Communication getCommunication(Puppet puppet, Proxy proxy) throws Exception {
        String protocol = puppet.getProtocol();
        String connLink = puppet.getConnLink();

        if ("http".equals(protocol)) {
            Map<String, String> headers = parseStringHeaders(puppet.getHeaders());
            return new HttpCommunication(connLink, "POST", headers, proxy);
        }
        if ("websocket".equals(protocol)) {
            WebSocketCommunication webSocket = new WebSocketCommunication(connLink, proxy);
            webSocket.connect();
            return webSocket;
        }
        if ("httpChunked".equals(protocol)) {
            Map<String, String> headers = parseStringHeaders(puppet.getHeaders());
            return new HttpChunkedCommunication(connLink, "POST", headers, proxy);
        }
        return null;
    }

    public void buildRequestAndResponseChain(Puppet puppet, JavaPuppetNode javaPuppetNode) throws Exception {
        List<RequestLayer> requestLayers = new ArrayList<>();
        List<ResponseLayer> responseLayers = new ArrayList<>();

        Puppet tempPuppet = puppet;
        int depth = 0;

        while (tempPuppet != null && depth < MAX_PARENT_DEPTH) {
            depth++;
            String reqDisguiseId = tempPuppet.getReqDisguiseId();
            if (reqDisguiseId != null) {
                Disguise reqDisguise = DisguiseManager.getInstance().getDisguiseById(reqDisguiseId);
                if (reqDisguise != null) {
                    RequestLayer requestLayer = new RequestLayer(
                            tempPuppet.getConnLink(),
                            parseStringHeaders(tempPuppet.getHeaders()),
                            reqDisguise);
                    requestLayers.add(0, requestLayer);
                }
            }
            String respDisguiseId = tempPuppet.getRespDisguiseId();
            if (respDisguiseId != null) {
                Disguise respDisguise = DisguiseManager.getInstance().getDisguiseById(respDisguiseId);
                if (respDisguise != null) {
                    responseLayers.add(new ResponseLayer(respDisguise));
                }
            }
            String parentId = tempPuppet.getParentPuppetId();
            if (parentId == null || "root".equals(parentId)) {
                break;
            }
            tempPuppet = puppetService.findPuppetById(parentId);
        }
        Collections.reverse(requestLayers);
        Collections.reverse(responseLayers);

        javaPuppetNode.setRequestLayers(requestLayers);
        javaPuppetNode.setResponseLayers(responseLayers);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseStringHeaders(String headersJson) {
        Object parsed = JsonUtil.fromJsonString(headersJson, Map.class);
        return parsed instanceof Map<?, ?> ? (Map<String, String>) parsed : new HashMap<>();
    }

    private void applyUrlStrategy(Puppet puppet, JavaPuppetNode javaPuppetNode) {
        String urlStrategyJson = puppet.getUrlStrategy();
        if (urlStrategyJson == null || urlStrategyJson.isBlank()) {
            return;
        }
        try {
            UrlStrategy strategy = (UrlStrategy) JsonUtil.fromJsonString(urlStrategyJson, UrlStrategy.class);
            if (strategy != null) {
                javaPuppetNode.setUrlStrategy(strategy);
            }
        } catch (Exception e) {
            logger.warn("解析 URL 随机化策略失败, puppetId={}: {}", puppet.getPuppetId(), e.getMessage());
        }
    }

    private void applyPaddingStrategy(Puppet puppet, JavaPuppetNode javaPuppetNode) {
        String paddingJson = puppet.getPaddingStrategy();
        if (paddingJson == null || paddingJson.isBlank()) {
            return;
        }
        try {
            PaddingStrategy strategy = (PaddingStrategy) JsonUtil.fromJsonString(paddingJson, PaddingStrategy.class);
            if (strategy != null) {
                javaPuppetNode.setPaddingStrategy(strategy);
            }
        } catch (Exception e) {
            logger.warn("解析 Padding 策略失败, puppetId={}: {}", puppet.getPuppetId(), e.getMessage());
        }
    }

    private void applyHeaderNoiseStrategy(Puppet puppet, JavaPuppetNode javaPuppetNode) {
        String noiseJson = puppet.getHeaderNoiseStrategy();
        if (noiseJson == null || noiseJson.isBlank()) {
            return;
        }
        try {
            HeaderNoiseStrategy strategy = (HeaderNoiseStrategy) JsonUtil.fromJsonString(noiseJson, HeaderNoiseStrategy.class);
            if (strategy != null) {
                javaPuppetNode.setHeaderNoiseStrategy(strategy);
            }
        } catch (Exception e) {
            logger.warn("解析 Header 噪声策略失败, puppetId={}: {}", puppet.getPuppetId(), e.getMessage());
        }
    }

    private void applyTlsFingerprintStrategy(Puppet puppet, Communication comm) {
        String tlsJson = puppet.getTlsFingerprintStrategy();
        if (tlsJson == null || tlsJson.isBlank()) {
            return;
        }
        if (!(comm instanceof HttpCommunication)) {
            return;
        }
        try {
            TlsFingerprintStrategy strategy = (TlsFingerprintStrategy) JsonUtil.fromJsonString(tlsJson, TlsFingerprintStrategy.class);
            if (strategy != null && strategy.isEnabled()) {
                ((HttpCommunication) comm).setTlsFingerprintStrategy(strategy);
            }
        } catch (Exception e) {
            logger.warn("解析 TLS 指纹策略失败, puppetId={}: {}", puppet.getPuppetId(), e.getMessage());
        }
    }

    private String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            return TYPE_JAVA;
        }
        return type.trim().toLowerCase();
    }
}
