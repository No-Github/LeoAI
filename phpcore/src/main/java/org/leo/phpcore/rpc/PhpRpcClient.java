package org.leo.phpcore.rpc;

import org.leo.core.component.runtime.ComponentArtifact;
import org.leo.core.entity.Disguise;
import org.leo.core.net.Communication;
import org.leo.core.net.impl.HttpCommunication;
import org.leo.core.net.layer.HeaderNoiseGenerator;
import org.leo.core.net.layer.HeaderNoiseStrategy;
import org.leo.core.net.layer.PaddingStrategy;
import org.leo.core.net.layer.PaddingUtil;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;
import org.leo.core.net.layer.UrlGenerator;
import org.leo.core.net.layer.UrlStrategy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Platform-side client for the PHP core M=0/1/2/3 protocol. */
public final class PhpRpcClient implements AutoCloseable {

    private static final int TEST = 0;
    private static final int LOAD = 2;
    private static final int INVOKE = 3;

    private final Communication communication;
    private final List<RequestLayer> requestLayers;
    private final List<ResponseLayer> responseLayers;
    private volatile String hostId;
    private int maxAttempts = 1;
    private UrlGenerator urlGenerator;
    private PaddingStrategy paddingStrategy;
    private HeaderNoiseGenerator headerNoiseGenerator;

    public PhpRpcClient(Communication communication,
                        List<RequestLayer> requestLayers,
                        List<ResponseLayer> responseLayers) {
        this.communication = communication;
        this.requestLayers = requestLayers == null ? List.of() : new ArrayList<>(requestLayers);
        this.responseLayers = responseLayers == null ? List.of() : new ArrayList<>(responseLayers);
    }

    public void setHostId(String hostId) { this.hostId = hostId; }

    public void setMaxReqCount(Integer count) {
        this.maxAttempts = count == null || count <= 0 ? 1 : count;
    }

    public void setUrlStrategy(UrlStrategy strategy) {
        if (strategy != null && strategy.isEnabled() && communication instanceof HttpCommunication http) {
            this.urlGenerator = new UrlGenerator(strategy, http.getUrl());
        } else {
            this.urlGenerator = null;
        }
    }

    public void setPaddingStrategy(PaddingStrategy strategy) { this.paddingStrategy = strategy; }

    public void setHeaderNoiseStrategy(HeaderNoiseStrategy strategy) {
        this.headerNoiseGenerator = strategy != null && strategy.isEnabled()
                ? new HeaderNoiseGenerator(strategy) : null;
    }

    public Map<String, Object> ping() throws Exception {
        return call(new LinkedHashMap<>(Map.of("M", TEST)));
    }

    public Map<String, Object> invokeComponent(String componentId, String digest,
                                                Map<String, Object> params) throws Exception {
        Map<String, Object> request = params == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(params);
        request.put("M", INVOKE);
        request.put("componentName", componentId);
        request.put("componentKey", componentKey(componentId, digest));
        putHostId(request);
        return call(request);
    }

    public Map<String, Object> putComponent(ComponentArtifact artifact) throws Exception {
        if (artifact == null) throw new IllegalArgumentException("artifact不能为空");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("M", LOAD);
        request.put("componentName", artifact.getComponentId());
        request.put("componentKey", componentKey(artifact.getComponentId(), artifact.getDigest()));
        request.put("source", new String(artifact.getContent(), StandardCharsets.UTF_8));
        putHostId(request);
        return call(request);
    }

    private void putHostId(Map<String, Object> request) {
        if (hostId != null && !hostId.isBlank()) request.put("hostId", hostId);
    }

    private String componentKey(String componentId, String digest) {
        if (componentId == null || componentId.isBlank()) {
            throw new IllegalArgumentException("componentId不能为空");
        }
        if (digest == null || !digest.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("digest格式错误");
        }
        try {
            byte[] value = MessageDigest.getInstance("SHA-256")
                    .digest(componentId.getBytes(StandardCharsets.UTF_8));
            StringBuilder slot = new StringBuilder(16);
            for (int index = 0; index < 8; index++) slot.append(String.format("%02x", value[index] & 0xff));
            return slot + digest;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> call(Map<String, Object> request) throws Exception {
        PaddingUtil.pad(request, paddingStrategy);
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                applyTransportStrategies();
                byte[] response = communication.sendRequest(encode(request));
                return normalize(decode(response));
            } catch (Exception e) {
                lastFailure = e;
            }
        }
        throw new IllegalStateException("PHP Puppet 通信失败: "
                + (lastFailure != null ? lastFailure.getMessage() : "unknown"), lastFailure);
    }

    private byte[] encode(Map<String, Object> request) throws Exception {
        if (requestLayers.isEmpty()) {
            throw new IllegalStateException("requestLayers为空，PHP Puppet 必须配置请求伪装");
        }
        byte[] current = requestLayers.get(0).getDisguise().encode(request);
        for (int i = 1; i < requestLayers.size(); i++) {
            RequestLayer previous = requestLayers.get(i - 1);
            Map<String, Object> relay = new LinkedHashMap<>();
            relay.put("M", 1);
            relay.put("rUrl", previous.getRUrl());
            relay.put("headers", previous.getHeaders() == null ? Map.of() : previous.getHeaders());
            relay.put("body", current);
            current = requestLayers.get(i).getDisguise().encode(relay);
        }
        return current;
    }

    private Map<String, Object> decode(byte[] response) throws Exception {
        if (responseLayers.isEmpty()) {
            throw new IllegalStateException("responseLayers为空，PHP Puppet 必须配置响应伪装");
        }
        byte[] current = response;
        Map<String, Object> decoded = null;
        for (int i = 0; i < responseLayers.size(); i++) {
            decoded = responseLayers.get(i).getDisguise().decode(current);
            if (i < responseLayers.size() - 1) {
                Object nested = decoded != null ? decoded.get("respData") : null;
                if (!(nested instanceof byte[] bytes)) {
                    throw new IllegalStateException("PHP Puppet 链式响应缺少 respData");
                }
                current = bytes;
            }
        }
        if (decoded == null || decoded.isEmpty()) {
            throw new IllegalStateException("PHP Puppet 响应为空");
        }
        return decoded;
    }

    private Map<String, Object> normalize(Map<String, Object> response) {
        Map<String, Object> result = new LinkedHashMap<>(response);
        Object code = response.get("code");
        result.put("code", code instanceof Number ? ((Number) code).intValue() : 500);
        Object message = response.get("msg");
        if (message == null) message = response.get("message");
        if (message != null) result.put("msg", message);
        Object data = response.get("data");
        if (data instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.putIfAbsent(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }

    private void applyTransportStrategies() {
        if (!(communication instanceof HttpCommunication http) || requestLayers.isEmpty()) return;
        RequestLayer outermost = requestLayers.get(requestLayers.size() - 1);
        Map<String, String> headers = new LinkedHashMap<>();
        Disguise disguise = outermost.getDisguise();
        if (disguise != null && disguise.getHeaders() != null) headers.putAll(disguise.getHeaders());
        if (outermost.getHeaders() != null) headers.putAll(outermost.getHeaders());
        headers.forEach(http::addHeader);
        if (urlGenerator != null) http.setRequestUrl(urlGenerator.nextUrl());
        if (headerNoiseGenerator != null) http.setRequestNoiseHeaders(headerNoiseGenerator.generate());
    }

    @Override
    public void close() throws Exception {
        if (communication instanceof AutoCloseable closeable) closeable.close();
    }
}
