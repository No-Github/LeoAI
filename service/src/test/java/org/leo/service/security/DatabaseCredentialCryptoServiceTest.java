package org.leo.service.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void rejectsPlaintextDatabaseValues() {
        DatabaseCredentialCryptoService crypto =
                new DatabaseCredentialCryptoService("test-master-key", tempDir.resolve("unused.key").toString());

        assertThrows(IllegalArgumentException.class, () -> crypto.decrypt("plaintext-value"));
    }

}
