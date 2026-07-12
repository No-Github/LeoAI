package org.leo.web.security;

import org.junit.jupiter.api.Test;
import org.leo.service.config.SystemConfigService;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PasswordPolicyTest {

    @Test
    void enforcesConfiguredLengthOnBackend() {
        SystemConfigService config = mock(SystemConfigService.class);
        when(config.getString("security.password.min.length", "8")).thenReturn("10");
        PasswordPolicy policy = new PasswordPolicy(config);

        assertThrows(IllegalArgumentException.class, () -> policy.validate("short"));
        assertDoesNotThrow(() -> policy.validate("long-enough"));
    }
}
