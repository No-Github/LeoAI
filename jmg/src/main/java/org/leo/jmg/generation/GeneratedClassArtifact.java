package org.leo.jmg.generation;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * 可直接下载的单个 Java Class 生成物描述。
 *
 * <p>生成服务保留原始文本输出，同时以 Base64 暴露各阶段的 Class 文件，便于调用方
 * 分别检查、校验或保存 Core、Shell 与 Injector。对象只保存不可变字符串和摘要，
 * 不向序列化层暴露可变的字节数组。</p>
 */
public final class GeneratedClassArtifact {

    private final String role;
    private final String className;
    private final String entryName;
    private final String fileName;
    private final int sizeBytes;
    private final String sha256;
    private final String contentEncoding;
    private final String mediaType;
    private final String content;

    private GeneratedClassArtifact(String role,
                                   String className,
                                   byte[] bytes) {
        if (isBlank(role)) {
            throw new IllegalArgumentException("Class 产物 role 不能为空");
        }
        if (isBlank(className)) {
            throw new IllegalArgumentException("Class 产物 className 不能为空");
        }
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Class 产物字节码不能为空");
        }
        this.role = role.trim();
        this.className = className.trim();
        this.entryName = this.className.replace('.', '/') + ".class";
        this.fileName = simpleClassName(this.className) + ".class";
        this.sizeBytes = bytes.length;
        this.sha256 = sha256(bytes);
        this.contentEncoding = "base64";
        this.mediaType = "application/java-vm";
        this.content = Base64.getEncoder().encodeToString(bytes);
    }

    public static GeneratedClassArtifact of(String role,
                                            String className,
                                            byte[] bytes) {
        return new GeneratedClassArtifact(role, className, bytes);
    }

    public String getRole() {
        return role;
    }

    public String getClassName() {
        return className;
    }

    /** Class 在 JAR/ZIP 中使用的完整路径。 */
    public String getEntryName() {
        return entryName;
    }

    /** 浏览器单文件下载时建议使用的文件名。 */
    public String getFileName() {
        return fileName;
    }

    public int getSizeBytes() {
        return sizeBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public String getContentEncoding() {
        return contentEncoding;
    }

    public String getMediaType() {
        return mediaType;
    }

    /** Base64 编码的完整 Class 文件内容。 */
    public String getContent() {
        return content;
    }

    private static String simpleClassName(String className) {
        int separator = className.lastIndexOf('.');
        return separator < 0 ? className : className.substring(separator + 1);
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder value = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                value.append(Character.forDigit((item >>> 4) & 0x0f, 16));
                value.append(Character.forDigit(item & 0x0f, 16));
            }
            return value.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JRE 缺少 SHA-256", e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
