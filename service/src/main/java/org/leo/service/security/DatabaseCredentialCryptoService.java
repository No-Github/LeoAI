package org.leo.service.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;

/** 使用 AES-256-GCM 加密数据库连接密码。 */
@Component
public class DatabaseCredentialCryptoService {

    private static final Logger log = LoggerFactory.getLogger(DatabaseCredentialCryptoService.class);
    static final String PREFIX = "enc:database:v1:";
    private static final byte[] AAD = "leo-database-connection-secret:v1".getBytes(StandardCharsets.UTF_8);
    private static final int NONCE_BYTES = 12;
    private static final int KEY_BYTES = 32;
    private static final int GCM_TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom secureRandom = new SecureRandom();

    public DatabaseCredentialCryptoService(
            @Value("${leo.database.secrets.master-key:}") String configuredMasterKey,
            @Value("${leo.database.secrets.key-file:.leo/database-secrets.key}") String configuredKeyFile) {
        boolean externalKey = configuredMasterKey != null && !configuredMasterKey.isBlank();
        byte[] keyBytes = externalKey ? deriveKey(configuredMasterKey) : loadOrCreateKeyFile(configuredKeyFile);
        this.key = new SecretKeySpec(keyBytes, "AES");
        if (externalKey) {
            log.info("Database Credential 使用外部主密钥");
        } else {
            log.info("Database Credential 使用本地密钥文件: {}", normalizedKeyFile(configuredKeyFile));
        }
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank() || isEncrypted(plaintext)) return plaintext;
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            secureRandom.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(AAD);
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, payload, 0, nonce.length);
            System.arraycopy(ciphertext, 0, payload, nonce.length, ciphertext.length);
            return PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Database Credential 加密失败", e);
        }
    }

    public String decrypt(String storedValue) {
        if (storedValue == null || storedValue.isBlank()) return storedValue;
        if (!isEncrypted(storedValue)) {
            throw new IllegalArgumentException("Database Credential 必须使用当前密文格式存储");
        }
        try {
            byte[] payload = Base64.getDecoder().decode(storedValue.substring(PREFIX.length()));
            if (payload.length <= NONCE_BYTES) throw new IllegalArgumentException("密文长度无效");
            byte[] nonce = new byte[NONCE_BYTES];
            byte[] ciphertext = new byte[payload.length - NONCE_BYTES];
            System.arraycopy(payload, 0, nonce, 0, NONCE_BYTES);
            System.arraycopy(payload, NONCE_BYTES, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(AAD);
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Database Credential 解密失败，请检查 LEO_DATABASE_MASTER_KEY 或本地密钥文件", e);
        }
    }

    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    private static byte[] deriveKey(String masterKey) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(masterKey.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("无法派生 Database Credential 主密钥", e);
        }
    }

    private static byte[] loadOrCreateKeyFile(String configuredKeyFile) {
        Path keyFile = normalizedKeyFile(configuredKeyFile);
        try {
            Path parent = keyFile.getParent();
            if (parent != null) Files.createDirectories(parent);
            if (!Files.exists(keyFile)) {
                byte[] generated = new byte[KEY_BYTES];
                new SecureRandom().nextBytes(generated);
                try {
                    Files.writeString(keyFile, Base64.getEncoder().encodeToString(generated),
                            StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                } catch (FileAlreadyExistsException ignored) {
                    // 并发启动时由另一个进程创建，下面统一读取。
                }
            }
            tightenFilePermissions(keyFile);
            byte[] decoded = Base64.getDecoder().decode(Files.readString(keyFile).trim());
            if (decoded.length != KEY_BYTES) {
                throw new IllegalStateException("Database Credential 密钥文件长度无效: " + keyFile);
            }
            return decoded;
        } catch (Exception e) {
            throw new IllegalStateException("无法加载或创建 Database Credential 密钥文件", e);
        }
    }

    private static Path normalizedKeyFile(String configuredKeyFile) {
        String value = configuredKeyFile == null || configuredKeyFile.isBlank()
                ? ".leo/database-secrets.key" : configuredKeyFile;
        return Path.of(value).toAbsolutePath().normalize();
    }

    private static void tightenFilePermissions(Path keyFile) {
        try {
            Set<PosixFilePermission> permissions = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(keyFile, permissions);
        } catch (UnsupportedOperationException | java.io.IOException ignored) {
            // 非 POSIX 文件系统依赖宿主访问控制。
        }
    }
}
