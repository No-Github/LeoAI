package org.leo.service.config;

import org.leo.dao.mapper.SystemConfigMapper;
import org.springframework.stereotype.Service;

@Service
public class SystemConfigService {

    private final SystemConfigMapper systemConfigMapper;

    public SystemConfigService(SystemConfigMapper systemConfigMapper) {
        this.systemConfigMapper = systemConfigMapper;
    }

    public String getString(String key, String defaultValue) {
        if (key == null || key.isBlank()) {
            return defaultValue;
        }
        String value = systemConfigMapper.findValueByKey(key.trim());
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    public void setString(String key, String value, String description) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("配置键不能为空");
        }
        systemConfigMapper.upsert(
                key.trim(),
                value == null ? "" : value.trim(),
                "string",
                description
        );
    }
}
