package org.leo.core.util;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Password hashing and verification for the current PBKDF2 storage format. */
public final class PasswordUtil {

    private static final String PREFIX = "pbkdf2-sha256";
    private static final int ITERATIONS = 210_000;
    private static final int MAX_VERIFY_ITERATIONS = 1_000_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    private static final int MIN_SALT_BYTES = 8;
    private static final int MAX_SALT_BYTES = 64;
    private static final int MIN_KEY_BYTES = 16;
    private static final int MAX_KEY_BYTES = 64;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private PasswordUtil() {}

    public static String hash(String input) {
        if (input == null) return null;
        byte[] salt = new byte[SALT_BYTES];
        SECURE_RANDOM.nextBytes(salt);
        byte[] derived = derive(input, salt, ITERATIONS, KEY_BITS);
        return PREFIX + "$" + ITERATIONS + "$"
                + Base64.getEncoder().withoutPadding().encodeToString(salt) + "$"
                + Base64.getEncoder().withoutPadding().encodeToString(derived);
    }

    public static boolean verify(String rawPassword, String storedPassword) {
        if (rawPassword == null || storedPassword == null) return false;
        if (!storedPassword.startsWith(PREFIX + "$")) {
            return false;
        }
        try {
            String[] parts = storedPassword.split("\\$", -1);
            if (parts.length != 4 || !PREFIX.equals(parts[0])) return false;
            int iterations = Integer.parseInt(parts[1]);
            if (iterations < 1 || iterations > MAX_VERIFY_ITERATIONS) return false;
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            if (salt.length < MIN_SALT_BYTES || salt.length > MAX_SALT_BYTES
                    || expected.length < MIN_KEY_BYTES || expected.length > MAX_KEY_BYTES) {
                return false;
            }
            byte[] actual = derive(rawPassword, salt, iterations, expected.length * 8);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] derive(String input, byte[] salt, int iterations, int keyBits) {
        PBEKeySpec spec = new PBEKeySpec(input.toCharArray(), salt, iterations, keyBits);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("无法计算密码哈希", e);
        } finally {
            spec.clearPassword();
        }
    }
}
