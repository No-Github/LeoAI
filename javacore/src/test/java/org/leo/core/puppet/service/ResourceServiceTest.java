package org.leo.core.puppet.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;

class ResourceServiceTest {

    @Test
    void expandsSinglePuppetPayloadToBothServerAliases() {
        byte[] bytes = new byte[]{1, 2, 3};
        Map<String, Object> response = new HashMap<String, Object>();
        response.put("data", bytes);

        ResourceService.normalizeResourceResponse(response);

        assertSame(bytes, response.get("data"));
        assertSame(bytes, response.get("bytecode"));
    }

    @Test
    void keepsLegacyBytecodeOnlyPayloadCompatible() {
        byte[] bytes = new byte[]{4, 5, 6};
        Map<String, Object> response = new HashMap<String, Object>();
        response.put("bytecode", bytes);

        ResourceService.normalizeResourceResponse(response);

        assertSame(bytes, response.get("data"));
        assertSame(bytes, response.get("bytecode"));
    }
}
