package org.leo.ai.channel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiSecretCryptoServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void encryptsWithRandomNonceAndDecrypts() {
        AiSecretCryptoService crypto = new AiSecretCryptoService("master-key-a", "unused");

        String first = crypto.encrypt("sk-sensitive");
        String second = crypto.encrypt("sk-sensitive");

        assertTrue(crypto.isEncrypted(first));
        assertNotEquals(first, second);
        assertEquals("sk-sensitive", crypto.decrypt(first));
        assertEquals("sk-sensitive", crypto.decrypt(second));
    }

    @Test
    void rejectsPlaintextDatabaseValues() {
        AiSecretCryptoService crypto = new AiSecretCryptoService("master-key-a", "unused");
        assertThrows(IllegalArgumentException.class, () -> crypto.decrypt("plaintext-value"));
    }

    @Test
    void rejectsCiphertextEncryptedWithDifferentMasterKey() {
        AiSecretCryptoService first = new AiSecretCryptoService("master-key-a", "unused");
        AiSecretCryptoService second = new AiSecretCryptoService("master-key-b", "unused");

        String encrypted = first.encrypt("sk-sensitive");
        assertThrows(IllegalStateException.class, () -> second.decrypt(encrypted));
    }

    @Test
    void reusesGeneratedLocalKeyFileAcrossRestarts() {
        Path keyFile = tempDir.resolve("ai.key");
        AiSecretCryptoService first = new AiSecretCryptoService("", keyFile.toString());
        String encrypted = first.encrypt("header-secret");

        AiSecretCryptoService second = new AiSecretCryptoService("", keyFile.toString());
        assertEquals("header-secret", second.decrypt(encrypted));
    }
}
