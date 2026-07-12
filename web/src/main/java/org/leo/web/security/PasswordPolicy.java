package org.leo.web.security;

import org.leo.service.config.SystemConfigService;
import org.springframework.stereotype.Service;

/** Central backend password validation used by every password-writing endpoint. */
@Service
public class PasswordPolicy {

    private static final int DEFAULT_MIN_LENGTH = 8;
    private static final int ABSOLUTE_MAX_LENGTH = 256;
    private final SystemConfigService configService;

    public PasswordPolicy(SystemConfigService configService) {
        this.configService = configService;
    }

    public void validate(String password) {
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("密码不能为空");
        }
        int minLength = configuredMinLength();
        if (password.length() < minLength) {
            throw new IllegalArgumentException("密码长度不能少于 " + minLength + " 位");
        }
        if (password.length() > ABSOLUTE_MAX_LENGTH) {
            throw new IllegalArgumentException("密码长度不能超过 " + ABSOLUTE_MAX_LENGTH + " 位");
        }
    }

    private int configuredMinLength() {
        try {
            int configured = Integer.parseInt(configService.getString(
                    "security.password.min.length", String.valueOf(DEFAULT_MIN_LENGTH)));
            return Math.max(6, Math.min(64, configured));
        } catch (RuntimeException ignored) {
            return DEFAULT_MIN_LENGTH;
        }
    }
}
