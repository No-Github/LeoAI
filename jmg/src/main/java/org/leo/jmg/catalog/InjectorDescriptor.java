package org.leo.jmg.catalog;

import org.leo.jmg.TransportProtocol;
import org.leo.jmg.mem.ServerType;

/**
 * 一个可生成的注入器组合。服务器、协议、公开形态和两个模板在同一处声明。
 */
public final class InjectorDescriptor {

    private final ServerType serverType;
    private final TransportProtocol protocol;
    private final String injectorName;
    private final String shellTemplateName;
    private final String injectorTemplateName;

    InjectorDescriptor(ServerType serverType,
                       TransportProtocol protocol,
                       String injectorName,
                       String shellTemplateName,
                       String injectorTemplateName) {
        this.serverType = serverType;
        this.protocol = protocol;
        this.injectorName = injectorName;
        this.shellTemplateName = shellTemplateName;
        this.injectorTemplateName = injectorTemplateName;
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
}
