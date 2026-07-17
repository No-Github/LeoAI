package org.leo.service.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseCredentialCryptoServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void encryptsAndDecryptsWithAuthenticatedCiphertext() {
        DatabaseCredentialCryptoService crypto =
                new DatabaseCredentialCryptoService("test-master-key", tempDir.resolve("unused.key").toString());

        String encrypted = crypto.encrypt("s3cret!");

        assertNotEquals("s3cret!", encrypted);
        assertTrue(crypto.isEncrypted(encrypted));
        assertEquals("s3cret!", crypto.decrypt(encrypted));
        assertEquals(encrypted, crypto.encrypt(encrypted));
    }

    @Test
    void decryptsCiphertextWrittenWithHistoricalJdbcPrefixAndAad() throws Exception {
        String masterKey = "test-master-key";
        DatabaseCredentialCryptoService crypto =
                new DatabaseCredentialCryptoService(masterKey, tempDir.resolve("unused.key").toString());

        String historicalCiphertext = historicalCiphertext(masterKey, "historical-secret");

        assertTrue(crypto.isEncrypted(historicalCiphertext));
        assertEquals("historical-secret", crypto.decrypt(historicalCiphertext));
    }

    private String historicalCiphertext(String masterKey, String plaintext) throws Exception {
        byte[] nonce = new byte[12];
        for (int i = 0; i < nonce.length; i++) nonce[i] = (byte) (i + 1);
        byte[] key = MessageDigest.getInstance("SHA-256")
                .digest(masterKey.getBytes(StandardCharsets.UTF_8));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD("leo-puppet-jdbc-secret:v1".getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] payload = new byte[nonce.length + encrypted.length];
        System.arraycopy(nonce, 0, payload, 0, nonce.length);
        System.arraycopy(encrypted, 0, payload, nonce.length, encrypted.length);
        return "enc:jdbc:v1:" + Base64.getEncoder().encodeToString(payload);
    }
}
