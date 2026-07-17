package org.leo.phpcore.puppet;

import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.core.puppet.capability.BasicInfoCapable;
import org.leo.core.puppet.capability.ComponentInvokeCapable;
import org.leo.core.puppet.capability.ComponentManageCapable;
import org.leo.core.puppet.capability.FileCapable;
import org.leo.core.puppet.capability.HostScopedCapable;
import org.leo.core.puppet.capability.HttpProxyCapable;
import org.leo.core.puppet.capability.HttpSenderCapable;
import org.leo.core.puppet.capability.LoadedComponentCacheCapable;
import org.leo.core.puppet.capability.LocalForwardCapable;
import org.leo.core.puppet.capability.PluginCapable;
import org.leo.core.puppet.capability.ReverseTunnelCapable;
import org.leo.core.puppet.capability.ScriptCapable;
import org.leo.core.puppet.capability.Socks5ProxyCapable;
import org.leo.core.puppet.capability.SqlCapable;
import org.leo.core.puppet.capability.TerminalCapable;
import org.leo.core.engine.proxy.NetworkProxyManager;
import org.leo.core.engine.socks5.Socks5ProxyStatistics;
import org.leo.core.runtime.PuppetRuntime;
import org.leo.core.runtime.RuntimeProfile;
import org.leo.core.puppet.http.HttpSenderEngine;
import org.leo.phpcore.rpc.PhpRpcClient;
import org.leo.phpcore.component.PhpComponentArtifactRegistry;
import org.leo.phpcore.database.PhpDatabaseConnectionAdapter;
import org.leo.core.component.runtime.ComponentArtifact;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** PHP implementation of the shared Puppet node and core capabilities. */
public final class PhpPuppetNode extends AbstractPuppetNode implements
        BasicInfoCapable, TerminalCapable, FileCapable, ScriptCapable, SqlCapable,
        ComponentInvokeCapable, ComponentManageCapable, PluginCapable,
        HttpSenderCapable, Socks5ProxyCapable, HttpProxyCapable,
        LocalForwardCapable, ReverseTunnelCapable,
        HostScopedCapable, LoadedComponentCacheCapable {

    private final PhpRpcClient rpcClient;
    private final PhpDatabaseConnectionAdapter databaseConnectionAdapter = new PhpDatabaseConnectionAdapter();
    private final PhpComponentArtifactRegistry componentRegistry;
    private final Set<String> loadedComponents = ConcurrentHashMap.newKeySet();
    private final NetworkProxyManager networkProxyManager = new NetworkProxyManager(this);
    private final HttpSenderEngine httpSenderEngine = new HttpSenderEngine() {
        @Override
        protected Map<String, Object> executeRequest(
                String method, String url, Map<String, String> headers, String body,
                int connectTimeout, int readTimeout, boolean followRedirects) throws Exception {
            return PhpPuppetNode.this.httpRequest(method, url, headers, body,
                    connectTimeout, readTimeout, followRedirects);
        }
    };
    private String hostId;

    public PhpPuppetNode(PhpRpcClient rpcClient, PhpComponentArtifactRegistry componentRegistry) {
        this.rpcClient = rpcClient;
        this.componentRegistry = componentRegistry;
        setRuntimeProfile(RuntimeProfile.minimal(PuppetRuntime.PHP));
    }

    @Override
    public void setHostId(String hostId) {
        this.hostId = hostId;
        rpcClient.setHostId(hostId);
    }

    @Override
    public void addLoadedComponent(String hostId, Set<String> components) {
        if (hostId != null && this.hostId == null) setHostId(hostId);
        if (components != null) loadedComponents.addAll(components);
    }

    @Override
    public Set<String> getLoadedComponents() {
        return Set.copyOf(loadedComponents);
    }

    @Override
    public Set<String> getAvailableComponents() {
        return componentRegistry.getComponentIds();
    }

    @Override
    public synchronized Map<String, Object> loadComponent(String componentId) throws Exception {
        ComponentArtifact artifact = componentRegistry.getRequired(componentId);
        Map<String, Object> result = rpcClient.putComponent(artifact);
        if (success(result)) loadedComponents.add(componentId);
        return result;
    }

    @Override
    public Map<String, Object> invokeComponent(String componentId, Map<String, Object> params) throws Exception {
        ComponentArtifact artifact = componentRegistry.getRequired(componentId);
        Map<String, Object> result = rpcClient.invokeComponent(componentId, artifact.getDigest(),
                params == null ? new LinkedHashMap<>() : params);
        if (missingComponent(result)) {
            synchronized (this) {
                loadedComponents.remove(componentId);
                Map<String, Object> loadResult = loadComponent(componentId);
                if (!success(loadResult)) return loadResult;
            }
            result = rpcClient.invokeComponent(componentId, artifact.getDigest(),
                    params == null ? new LinkedHashMap<>() : params);
        }
        if (!missingComponent(result)) loadedComponents.add(componentId);
        return result;
    }

    @Override
    public void unloadComponent(String componentId) {
        loadedComponents.remove(componentId);
    }

    @Override
    public Map<String, Object> testConnection() throws Exception {
        Map<String, Object> result = rpcClient.ping();
        Object reportedHostId = result.get("hostId");
        if (reportedHostId != null) setHostId(String.valueOf(reportedHostId));
        loadedComponents.clear();
        addStringValues(result.get("components"), loadedComponents);
        return result;
    }

    @Override
    public Map<String, Object> getBasicInfo() throws Exception {
        return invoke("BasicInfoComponent", "get", Map.of());
    }

    @Override
    public Map<String, Object> execCommand(String type, String cmd, String processId) throws Exception {
        String action = type == null ? "" : type.trim().toLowerCase();
        if (!Set.of("write", "read", "resize", "stop").contains(action)) {
            return error(400, "PHP 虚拟终端操作不受支持: " + action);
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("processId", processId);
        params.put("cmd", cmd == null ? "" : cmd);
        return invoke("ExecCommandComponent", action, params);
    }

    @Override
    public Map<String, Object> execSimpleCommand(String cmd) throws Exception {
        return execSimpleCommand(cmd, 30);
    }

    @Override
    public Map<String, Object> execSimpleCommand(String cmd, int timeoutSeconds) throws Exception {
        return invoke("ExecCommandSimpleComponent", "exec",
                Map.of("cmd", cmd, "timeoutSeconds", Math.max(1, Math.min(timeoutSeconds, 120))));
    }

    @Override
    public Map<String, Object> getFileList(String path) throws Exception {
        return invoke("FileComponent", "list", Map.of("path", path));
    }

    @Override
    public Map<String, Object> getRootList() throws Exception {
        return invoke("FileComponent", "roots", Map.of());
    }

    @Override
    public Map<String, Object> fileDownloadChunk(String path, long size, long offset) throws Exception {
        return invoke("FileDownloadComponent", "download",
                Map.of("path", path, "size", size, "offset", offset));
    }

    @Override
    public Map<String, Object> fileUploadChunk(String path, long offset, byte[] data) throws Exception {
        return invoke("FileUploadComponent", "upload",
                Map.of("path", path, "offset", offset, "data", data));
    }

    @Override
    public Map<String, Object> getFileMD5(String path) throws Exception {
        return invoke("FileComponent", "md5", Map.of("path", path));
    }

    @Override
    public Map<String, Object> createDir(String dirName) throws Exception {
        return invoke("FileComponent", "mkdir", Map.of("path", dirName));
    }

    @Override
    public Map<String, Object> deleteFile(String path) throws Exception {
        return invoke("FileComponent", "delete", Map.of("path", path));
    }

    @Override
    public Map<String, Object> copyFile(String srcPath, String destPath, String conflictStrategy) throws Exception {
        return invoke("FileComponent", "copy", optionalConflict(srcPath, destPath, conflictStrategy, "destPath"));
    }

    @Override
    public Map<String, Object> moveFile(String srcPath, String newPath, String conflictStrategy) throws Exception {
        return invoke("FileComponent", "move", optionalConflict(srcPath, newPath, conflictStrategy, "newPath"));
    }

    @Override
    public Map<String, Object> createFile(String path, String content) throws Exception {
        return invoke("FileComponent", "create", Map.of("path", path, "content", content));
    }

    @Override
    public Map<String, Object> compressFile(String src, String des, String excludePattern) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("src", src); params.put("des", des);
        if (excludePattern != null) params.put("exclude", excludePattern);
        return invoke("CompressComponent", "compress", params);
    }

    @Override
    public Map<String, Object> editFile(String path, String content) throws Exception {
        return invoke("FileComponent", "edit", Map.of("path", path, "content", content));
    }

    @Override
    public Map<String, Object> decompressFile(String src, String des, String format) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("src", src); params.put("des", des);
        if (format != null) params.put("format", format);
        return invoke("DecompressComponent", "decompress", params);
    }

    @Override
    public Map<String, Object> execScript(String language, String script) throws Exception {
        return invoke("ExecScriptComponent", "exec", Map.of("language", language, "script", script));
    }

    @Override
    public Map<String, Object> httpRequest(String method, String url, Map<String, String> headers,
                                           String body, int connectTimeout, int readTimeout,
                                           boolean followRedirects) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("method", method == null ? "GET" : method);
        params.put("url", url);
        if (headers != null && !headers.isEmpty()) params.put("headers", new LinkedHashMap<>(headers));
        if (body != null) params.put("body", body);
        if (connectTimeout > 0) params.put("connectTimeout", connectTimeout);
        if (readTimeout > 0) params.put("readTimeout", readTimeout);
        params.put("followRedirects", followRedirects);
        return invoke("HttpRequestComponent", "send", params);
    }

    @Override
    public Map<String, Object> sendRawHttp(String rawHttp, String targetHost, int targetPort,
                                           boolean useTls, boolean followRedirects,
                                           int connectTimeout, int readTimeout) throws Exception {
        return httpSenderEngine.sendRawHttp(rawHttp, targetHost, targetPort, useTls,
                followRedirects, connectTimeout, readTimeout);
    }

    @Override
    public Map<String, Object> startFuzz(String rawHttp, Map<String, List<String>> payloads,
                                         String targetHost, int targetPort, boolean useTls,
                                         int threads, int delayMs,
                                         Map<String, Object> matchRules) throws Exception {
        return httpSenderEngine.startFuzz(rawHttp, payloads, targetHost, targetPort, useTls,
                threads, delayMs, matchRules);
    }

    @Override
    public Map<String, Object> queryFuzz(String taskId) {
        return httpSenderEngine.queryFuzz(taskId);
    }

    @Override
    public Map<String, Object> stopFuzz(String taskId) {
        return httpSenderEngine.stopFuzz(taskId);
    }

    @Override
    public Map<String, Object> startSocks5Proxy(int port) throws Exception {
        return networkProxyManager.startSocks5Proxy(port);
    }

    @Override
    public Map<String, Object> stopSocks5Proxy() {
        return networkProxyManager.stopSocks5Proxy();
    }

    @Override
    public Map<String, Object> getSocks5ProxyStatus() {
        return networkProxyManager.getSocks5ProxyStatus();
    }

    @Override
    public Socks5ProxyStatistics.StatisticsSnapshot getSocks5ProxyStatistics() {
        return networkProxyManager.getSocks5ProxyStatistics();
    }

    @Override
    public Map<String, Object> startHttpProxy(int port) throws Exception {
        return networkProxyManager.startHttpProxy(port);
    }

    @Override
    public Map<String, Object> stopHttpProxy() {
        return networkProxyManager.stopHttpProxy();
    }

    @Override
    public Map<String, Object> getHttpProxyStatus() {
        return networkProxyManager.getHttpProxyStatus();
    }

    @Override
    public Socks5ProxyStatistics.StatisticsSnapshot getHttpProxyStatistics() {
        return networkProxyManager.getHttpProxyStatistics();
    }

    @Override
    public Map<String, Object> startLocalForward(int localPort, String targetHost, int targetPort) throws Exception {
        return networkProxyManager.startLocalForward(localPort, targetHost, targetPort);
    }

    @Override
    public Map<String, Object> stopLocalForward(int localPort) {
        return networkProxyManager.stopLocalForward(localPort);
    }

    @Override
    public Map<String, Object> stopAllLocalForwards() {
        return networkProxyManager.stopAllLocalForwards();
    }

    @Override
    public List<Map<String, Object>> listLocalForwards() {
        return networkProxyManager.listLocalForwards();
    }

    @Override
    public Socks5ProxyStatistics.StatisticsSnapshot getLocalForwardStatistics(int localPort) {
        return networkProxyManager.getLocalForwardStatistics(localPort);
    }

    @Override
    public Map<String, Object> startReverseTunnel(int remoteListenPort, String bindAddr,
                                                   String forwardHost, int forwardPort) throws Exception {
        return networkProxyManager.startReverseTunnel(remoteListenPort, bindAddr, forwardHost, forwardPort);
    }

    @Override
    public Map<String, Object> stopReverseTunnel(String listenId) {
        return networkProxyManager.stopReverseTunnel(listenId);
    }

    @Override
    public Map<String, Object> stopAllReverseTunnels() {
        return networkProxyManager.stopAllReverseTunnels();
    }

    @Override
    public List<Map<String, Object>> listReverseTunnels() {
        return networkProxyManager.listReverseTunnels();
    }

    @Override
    public Socks5ProxyStatistics.StatisticsSnapshot getReverseTunnelStatistics(String listenId) {
        return networkProxyManager.getReverseTunnelStatistics(listenId);
    }

    @Override
    public Map<String, Object> executeSql(org.leo.core.puppet.database.DatabaseConnectionSpec connection,
                                          String sqlScript) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>(databaseConnectionAdapter.adapt(connection));
        params.put("sql", sqlScript);
        return invoke("DatabaseComponent", "exec", params);
    }

    @Override
    public Map<String, Object> invokePlugin(String pluginId, String source,
                                            Map<String, Object> params) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pluginId", pluginId);
        payload.put("source", source);
        payload.put("pluginParams", params == null ? Map.of() : params);
        payload.put("action", "invoke");
        return invokeComponent("PluginComponent", payload);
    }

    private boolean success(Map<String, Object> result) {
        return result != null && result.get("code") instanceof Number number
                && number.intValue() >= 200 && number.intValue() < 300;
    }

    private boolean missingComponent(Map<String, Object> result) {
        return result != null && result.get("code") instanceof Number number && number.intValue() == 424;
    }

    @Override
    public void close() throws Exception {
        networkProxyManager.close();
        httpSenderEngine.close();
        rpcClient.close();
    }

    private Map<String, Object> invoke(String component, String action,
                                       Map<String, Object> params) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>(params);
        payload.put("action", action);
        return invokeComponent(component, payload);
    }

    private Map<String, Object> optionalConflict(String src, String target,
                                                  String strategy, String targetKey) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("path", src);
        params.put(targetKey, target);
        if (strategy != null && !strategy.isBlank()) params.put("conflictStrategy", strategy);
        return params;
    }

    private void addStringValues(Object raw, Set<String> target) {
        if (raw instanceof Iterable<?> iterable) {
            for (Object item : iterable) if (item != null) target.add(String.valueOf(item));
        } else if (raw instanceof Object[] array) {
            for (Object item : array) if (item != null) target.add(String.valueOf(item));
        }
    }

    private Map<String, Object> error(int code, String message) {
        return new LinkedHashMap<>(Map.of("code", code, "msg", message));
    }
}
