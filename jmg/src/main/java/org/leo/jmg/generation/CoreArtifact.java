package org.leo.jmg.generation;

import org.leo.jmg.ServletNamespace;
import org.leo.jmg.TargetJavaVersion;
import org.leo.jmg.TransportProtocol;

import java.security.MessageDigest;

/**
 * 独立生成的 LeoCore 制品。字节码只在服务端阶段间传递，不应进入 LLM 上下文。
 */
public final class CoreArtifact {

    private final String coreClassName;
    private final byte[] bytecode;
    private final String sha256;
    private final TransportProtocol protocol;
    private final TargetJavaVersion targetJavaVersion;
    private final ServletNamespace servletNamespace;
    private final long obfuscationSeed;

    public CoreArtifact(String coreClassName,
                        byte[] bytecode,
                        TransportProtocol protocol,
                        TargetJavaVersion targetJavaVersion,
                        ServletNamespace servletNamespace,
                        long obfuscationSeed) {
        if (isBlank(coreClassName)) {
            throw new IllegalArgumentException("coreClassName 不能为空");
        }
        if (bytecode == null || bytecode.length == 0) {
            throw new IllegalArgumentException("Core 字节码不能为空");
        }
        this.coreClassName = coreClassName.trim();
        this.bytecode = bytecode.clone();
        this.sha256 = sha256(bytecode);
        this.protocol = protocol == null ? TransportProtocol.HTTP : protocol;
        this.targetJavaVersion = targetJavaVersion == null
                ? TargetJavaVersion.AUTO : targetJavaVersion;
        this.servletNamespace = servletNamespace == null
                ? ServletNamespace.AUTO : servletNamespace;
        this.obfuscationSeed = obfuscationSeed;
    }

    public String getCoreClassName() {
        return coreClassName;
    }

    public byte[] getBytecode() {
        return bytecode.clone();
    }

    public int getBytecodeSize() {
        return bytecode.length;
    }

    public String getSha256() {
        return sha256;
    }

    public TransportProtocol getProtocol() {
        return protocol;
    }

    public TargetJavaVersion getTargetJavaVersion() {
        return targetJavaVersion;
    }

    public ServletNamespace getServletNamespace() {
        return servletNamespace.resolve();
    }

    public long getObfuscationSeed() {
        return obfuscationSeed;
    }

    private static String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append(String.format("%02x", item & 0xff));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("无法计算 Core SHA-256", e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
