package org.leo.service.puppetnode;

import org.leo.core.entity.Disguise;
import org.leo.core.entity.Puppet;
import org.leo.core.entity.User;
import org.leo.core.manager.DisguiseManager;
import org.leo.core.net.Communication;
import org.leo.core.net.impl.HttpChunkedCommunication;
import org.leo.core.net.impl.HttpCommunication;
import org.leo.core.net.impl.WebSocketCommunication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;
import org.leo.core.net.layer.TlsFingerprintStrategy;
import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.core.runtime.PuppetNodeCreationContext;
import org.leo.core.runtime.PuppetRuntime;
import org.leo.core.runtime.PuppetRuntimeModule;
import org.leo.core.util.json.JsonUtil;
import org.leo.service.PuppetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runtime factory for Puppet nodes.
 *
 * <p>The transport protocol (http/websocket/httpChunked) is independent from the
 * node runtime type. Java and PHP are selected through equal-status
 * {@link PuppetRuntimeModule} implementations.
 */
@Service
public class PuppetNodeFactory implements PuppetNodeCreationContext {

    private static final Logger logger = LoggerFactory.getLogger(PuppetNodeFactory.class);
    private static final int MAX_PARENT_DEPTH = 100;
    private static final int PROXY_ENABLED = 1;
    private final PuppetService puppetService;
    private final Map<PuppetRuntime, PuppetRuntimeModule> runtimeModules;

    public PuppetNodeFactory(PuppetService puppetService,
                             List<PuppetRuntimeModule> runtimeModules) {
        this.puppetService = puppetService;
        this.runtimeModules = indexRuntimeModules(runtimeModules);
    }

    public AbstractPuppetNode createLiveNode(Puppet puppet, User user) throws Exception {
        if (puppet == null) {
            throw new IllegalArgumentException("Puppet不能为空");
        }

        PuppetRuntime runtime = PuppetRuntime.from(puppet.getType());
        PuppetRuntimeModule module = runtimeModules.get(runtime);
        if (module == null) {
            throw new IllegalArgumentException("未安装 Puppet 运行时模块: " + runtime.getValue());
        }
        if (!module.isReady()) {
            throw new IllegalArgumentException("Puppet 运行时模块尚未就绪: " + runtime.getValue());
        }

        AbstractPuppetNode node = module.createNode(puppet, user, this);
        if (node == null) {
            throw new IllegalStateException("运行时模块返回空节点: " + runtime.getValue());
        }
        if (node.getRuntime() != runtime) {
            throw new IllegalStateException("运行时模块返回了错误类型节点: expected="
                    + runtime.getValue() + ", actual=" + node.getRuntime().getValue());
        }
        return node;
    }

    private Map<PuppetRuntime, PuppetRuntimeModule> indexRuntimeModules(
            List<PuppetRuntimeModule> modules) {
        EnumMap<PuppetRuntime, PuppetRuntimeModule> indexed = new EnumMap<>(PuppetRuntime.class);
        if (modules == null) {
            return Collections.unmodifiableMap(indexed);
        }
        for (PuppetRuntimeModule module : modules) {
            if (module == null || module.getRuntime() == null) continue;
            PuppetRuntimeModule previous = indexed.putIfAbsent(module.getRuntime(), module);
            if (previous != null) {
                throw new IllegalStateException("重复的 Puppet 运行时模块: "
                        + module.getRuntime().getValue());
            }
        }
        return Collections.unmodifiableMap(indexed);
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

    @Override
    public Communication createCommunication(Puppet puppet) throws Exception {
        Puppet transportPuppet = resolveTransportPuppet(puppet);
        Communication communication = getCommunication(transportPuppet, getProxy(transportPuppet));
        if (communication == null) {
            throw new IllegalArgumentException("无法创建通信连接，协议不支持: "
                    + transportPuppet.getProtocol());
        }
        applyTlsFingerprintStrategy(puppet, communication);
        return communication;
    }

    @Override
    public TransportLayers createTransportLayers(Puppet puppet) throws Exception {
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
        return new TransportLayers(requestLayers, responseLayers);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseStringHeaders(String headersJson) {
        Object parsed = JsonUtil.fromJsonString(headersJson, Map.class);
        return parsed instanceof Map<?, ?> ? (Map<String, String>) parsed : new HashMap<>();
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

}
