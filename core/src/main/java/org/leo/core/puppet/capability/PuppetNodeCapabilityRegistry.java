package org.leo.core.puppet.capability;

import org.leo.core.puppet.AbstractPuppetNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Stable capability names exposed to UI and orchestration layers.
 */
public final class PuppetNodeCapabilityRegistry {

    private static final List<CapabilityDescriptor> DESCRIPTORS = List.of(
            descriptor("basicInfo", BasicInfoCapable.class),
            descriptor("command", CommandCapable.class),
            descriptor("terminal", TerminalCapable.class),
            descriptor("file", FileCapable.class),
            descriptor("networkInfo", NetworkInfoCapable.class),
            descriptor("disk", DiskCapable.class),
            descriptor("sql", SqlCapable.class),
            descriptor("script", ScriptCapable.class),
            descriptor("resource", ResourceCapable.class),
            descriptor("clipboard", ClipboardCapable.class),
            descriptor("browserData", BrowserDataCapable.class),
            descriptor("httpSender", HttpSenderCapable.class),
            descriptor("process", ProcessCapable.class),
            descriptor("registry", RegistryCapable.class),
            descriptor("scheduledTask", ScheduledTaskCapable.class),
            descriptor("service", ServiceCapable.class),
            descriptor("eventLog", EventLogCapable.class),
            descriptor("firewall", FirewallCapable.class),
            descriptor("networkConnection", NetworkConnectionCapable.class),
            descriptor("userAccount", UserAccountCapable.class),
            descriptor("networkShare", NetworkShareCapable.class),
            descriptor("installedSoftware", InstalledSoftwareCapable.class),
            descriptor("wifiProfile", WifiProfileCapable.class),
            descriptor("persistence", PersistenceCapable.class),
            descriptor("docker", DockerCapable.class),
            descriptor("suidCapability", SuidCapabilityCapable.class),
            descriptor("httpProxy", HttpProxyCapable.class),
            descriptor("localForward", LocalForwardCapable.class),
            descriptor("reverseTunnel", ReverseTunnelCapable.class),
            descriptor("socks5Proxy", Socks5ProxyCapable.class),
            descriptor("scan", ScanCapable.class),
            descriptor("componentInvoke", ComponentInvokeCapable.class),
            descriptor("componentManage", ComponentManageCapable.class),
            descriptor("catalinaManage", CatalinaManageCapable.class),
            descriptor("javaPlugin", JavaPluginCapable.class),
            descriptor("credentialHarvest", CredentialHarvestCapable.class)
    );

    private PuppetNodeCapabilityRegistry() {
    }

    public static List<CapabilityDescriptor> descriptors() {
        return DESCRIPTORS;
    }

    public static List<String> listSupported(AbstractPuppetNode node) {
        if (node == null) {
            return Collections.emptyList();
        }
        List<String> supported = new ArrayList<>();
        for (CapabilityDescriptor descriptor : DESCRIPTORS) {
            if (descriptor.type().isInstance(node)) {
                supported.add(descriptor.name());
            }
        }
        return supported;
    }

    public static boolean supports(AbstractPuppetNode node, String capabilityName) {
        if (node == null || capabilityName == null || capabilityName.isBlank()) {
            return false;
        }
        for (CapabilityDescriptor descriptor : DESCRIPTORS) {
            if (descriptor.name().equals(capabilityName) && descriptor.type().isInstance(node)) {
                return true;
            }
        }
        return false;
    }

    private static CapabilityDescriptor descriptor(String name, Class<?> type) {
        return new CapabilityDescriptor(name, type);
    }

    public record CapabilityDescriptor(String name, Class<?> type) {
    }
}
