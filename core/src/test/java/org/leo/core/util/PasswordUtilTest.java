package org.leo.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordUtilTest {

    @Test
    void hashesWithRandomSaltAndVerifiesPassword() {
        String first = PasswordUtil.hash("correct horse battery staple");
        String second = PasswordUtil.hash("correct horse battery staple");

        assertNotEquals(first, second);
        assertTrue(PasswordUtil.verify("correct horse battery staple", first));
        assertFalse(PasswordUtil.verify("wrong", first));
        assertFalse(PasswordUtil.needsRehash(first));
    }

    @Test
    void acceptsLegacyMd5AndMarksItForUpgrade() {
        String legacy = PasswordUtil.md5("legacy-password");

        assertTrue(PasswordUtil.verify("legacy-password", legacy));
        assertFalse(PasswordUtil.verify("wrong", legacy));
        assertTrue(PasswordUtil.needsRehash(legacy));
    }
}
