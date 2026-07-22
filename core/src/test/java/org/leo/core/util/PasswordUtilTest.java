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
        assertFalse(PasswordUtil.verify("correct horse battery staple", "5f4dcc3b5aa765d61d8327deb882cf99"));
    }

    @Test
    void rejectsUnboundedOrMalformedPbkdf2Parameters() {
        String tinySalt = "pbkdf2-sha256$210000$AA$AAAAAAAAAAAAAAAAAAAAAA";
        String excessiveWork = "pbkdf2-sha256$2147483647$AAAAAAAAAAAAAAAAAAAAAA$"
                + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

        assertFalse(PasswordUtil.verify("password", tinySalt));
        assertFalse(PasswordUtil.verify("password", excessiveWork));
    }
}
