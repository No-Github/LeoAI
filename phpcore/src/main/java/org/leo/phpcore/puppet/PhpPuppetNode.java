package org.leo.phpcore.puppet;

import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.core.puppet.capability.BasicInfoCapable;
import org.leo.core.puppet.capability.CommandCapable;
import org.leo.core.puppet.capability.ComponentInvokeCapable;
import org.leo.core.puppet.capability.ComponentManageCapable;
import org.leo.core.puppet.capability.FileCapable;
import org.leo.core.puppet.capability.HostScopedCapable;
import org.leo.core.puppet.capability.LoadedComponentCacheCapable;
import org.leo.core.puppet.capability.PluginCapable;
import org.leo.core.puppet.capability.ScriptCapable;
import org.leo.core.puppet.capability.SqlCapable;
import org.leo.core.runtime.PuppetRuntime;
import org.leo.core.runtime.RuntimeProfile;
import org.leo.phpcore.rpc.PhpRpcClient;
import org.leo.phpcore.component.PhpComponentArtifactRegistry;
import org.leo.core.component.runtime.ComponentArtifact;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** PHP implementation of the shared Puppet node and core capabilities. */
public final class PhpPuppetNode extends AbstractPuppetNode implements
        BasicInfoCapable, CommandCapable, FileCapable, ScriptCapable, SqlCapable,
        ComponentInvokeCapable, ComponentManageCapable, PluginCapable,
        HostScopedCapable, LoadedComponentCacheCapable {

    private final PhpRpcClient rpcClient;
    private final PhpComponentArtifactRegistry componentRegistry;
    private final Set<String> loadedComponents = ConcurrentHashMap.newKeySet();
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
        if (!"write".equalsIgnoreCase(type)) {
            return error(400, "PHP 请求运行时不支持交互式终端 read/stop");
        }
        return execSimpleCommand(cmd);
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
    public Map<String, Object> execSql(String driverClassName, String jdbcUrl, String user,
                                       String password, String sqlScript) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("driver", driverClassName);
        params.put("url", jdbcUrl);
        params.put("user", user);
        params.put("password", password);
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
