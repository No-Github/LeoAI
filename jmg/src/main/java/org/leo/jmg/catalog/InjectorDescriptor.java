package org.leo.jmg.catalog;

import org.leo.jmg.TransportProtocol;
import org.leo.jmg.ServletNamespace;
import org.leo.jmg.mem.ServerType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 一个可生成的注入器组合。服务器、协议、公开形态和两个模板在同一处声明。
 */
public final class InjectorDescriptor {

    private final ServerType serverType;
    private final TransportProtocol protocol;
    private final String injectorName;
    private final String shellTemplateName;
    private final String injectorTemplateName;
    private final MountType mountType;
    private final boolean supportsJakarta;
    private final List<String> supportedServerVersions;
    private final List<String> supportedPackers;
    private final boolean supportsStaticInitialize;

    InjectorDescriptor(ServerType serverType,
                       TransportProtocol protocol,
                       String injectorName,
                       String shellTemplateName,
                       String injectorTemplateName,
                       boolean supportsJakarta,
                       List<String> supportedServerVersions,
                       List<String> supportedPackers,
                       boolean supportsStaticInitialize) {
        this.serverType = serverType;
        this.protocol = protocol;
        this.injectorName = injectorName;
        this.shellTemplateName = shellTemplateName;
        this.injectorTemplateName = injectorTemplateName;
        this.mountType = MountType.fromInjectorName(injectorName);
        this.supportsJakarta = supportsJakarta;
        this.supportedServerVersions = supportedServerVersions == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(
                        new ArrayList<String>(supportedServerVersions));
        this.supportedPackers = supportedPackers == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(supportedPackers));
        this.supportsStaticInitialize = supportsStaticInitialize;
    }

    public ServerType getServerType() {
        return serverType;
    }

    public TransportProtocol getProtocol() {
        return protocol;
    }

    public String getInjectorName() {
        return injectorName;
    }

    public String getShellTemplateName() {
        return shellTemplateName;
    }

    public String getInjectorTemplateName() {
        return injectorTemplateName;
    }

    public MountType getMountType() {
        return mountType;
    }

    public boolean supportsServletNamespace(ServletNamespace namespace) {
        return namespace != ServletNamespace.JAKARTA || supportsJakarta;
    }

    public List<String> getSupportedServletNamespaces() {
        return supportsJakarta
                ? Collections.unmodifiableList(Arrays.asList("javax", "jakarta"))
                : Collections.singletonList("javax");
    }

    public List<String> getSupportedServerVersions() {
        return supportedServerVersions;
    }

    public boolean requiresServerVersion() {
        return !supportedServerVersions.isEmpty();
    }

    public boolean supportsServerVersion(String serverVersion) {
        return !requiresServerVersion()
                || (serverVersion != null
                && supportedServerVersions.contains(serverVersion.trim()));
    }

    public List<String> getSupportedPackers() {
        return supportedPackers;
    }

    public boolean supportsPacker(String packerName) {
        if (supportedPackers.isEmpty()) return true;
        if (packerName == null) return false;
        for (String supported : supportedPackers) {
            if (supported.equalsIgnoreCase(packerName.trim())) return true;
        }
        return false;
    }

    public boolean supportsStaticInitialize() {
        return supportsStaticInitialize;
    }

    public boolean supportsUrlPattern() {
        return mountType.supportsUrlPattern();
    }

    public boolean supportsHeaderGate() {
        return protocol.isHttpFamily()
                || protocol == TransportProtocol.WEBSOCKET;
    }
}
