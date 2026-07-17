package org.leo.phpcore.component;

import org.leo.core.component.runtime.ComponentArtifact;
import org.leo.core.component.runtime.ComponentDeliveryMode;
import org.leo.core.runtime.PuppetRuntime;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Classpath-backed PHP component artifacts deployed to targets on demand. */
@Component
public final class PhpComponentArtifactRegistry {

    public static final String VERSION = "1.0.0";
    private static final Set<String> COMPONENT_IDS = Set.of(
            "BasicInfoComponent", "ExecCommandComponent", "ExecCommandSimpleComponent", "FileComponent",
            "FileDownloadComponent", "FileUploadComponent", "ExecScriptComponent",
            "DatabaseComponent", "CompressComponent", "DecompressComponent", "PluginComponent",
            "HttpRequestComponent", "ProxyForwardComponent", "ReverseTunnelComponent");

    private final Map<String, ComponentArtifact> artifacts;

    public PhpComponentArtifactRegistry() {
        Map<String, ComponentArtifact> loaded = new LinkedHashMap<>();
        COMPONENT_IDS.stream().sorted().forEach(id -> loaded.put(id, load(id)));
        this.artifacts = Collections.unmodifiableMap(loaded);
    }

    public Set<String> getComponentIds() {
        return artifacts.keySet();
    }

    public ComponentArtifact getRequired(String componentId) {
        if (componentId == null || componentId.isBlank()) {
            throw new IllegalArgumentException("componentId不能为空");
        }
        ComponentArtifact artifact = artifacts.get(componentId.trim());
        if (artifact == null) {
            throw new IllegalArgumentException("PHP 组件不存在: " + componentId);
        }
        return artifact;
    }

    private ComponentArtifact load(String componentId) {
        String resource = "/components/" + componentId + ".php";
        try (InputStream input = getClass().getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("PHP 组件资源不存在: " + resource);
            byte[] source = input.readAllBytes();
            return new ComponentArtifact(componentId, VERSION, sha256(source), PuppetRuntime.PHP,
                    ComponentDeliveryMode.DISK_CACHE, source);
        } catch (IOException e) {
            throw new IllegalStateException("读取 PHP 组件失败: " + componentId, e);
        }
    }

    private String sha256(byte[] source) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source);
            StringBuilder value = new StringBuilder(digest.length * 2);
            for (byte item : digest) value.append(String.format("%02x", item & 0xff));
            return value.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 初始化失败", e);
        }
    }
}
