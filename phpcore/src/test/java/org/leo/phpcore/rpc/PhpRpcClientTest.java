package org.leo.phpcore.rpc;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.Disguise;
import org.leo.core.net.Communication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;
import org.leo.core.util.json.PortableJsonCodec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhpRpcClientTest {

    @Test
    void sendsCoreTestMethodAndKeepsDirectResponseFields() throws Exception {
        Disguise portable = new PortableDisguise();
        Communication communication = data -> {
            Map<String, Object> request = PortableJsonCodec.decode(data);
            assertEquals(0, ((Number) request.get("M")).intValue());
            return PortableJsonCodec.encode(Map.of(
                    "code", 200,
                    "msg", "pong",
                    "hostId", "php-host",
                    "components", List.of("BasicInfoComponent")
            ));
        };

        PhpRpcClient client = new PhpRpcClient(communication,
                List.of(new RequestLayer("/", Map.of(), portable)),
                List.of(new ResponseLayer(portable)));
        Map<String, Object> result = client.ping();

        assertEquals(200, result.get("code"));
        assertEquals("pong", result.get("msg"));
        assertEquals("php-host", result.get("hostId"));
        assertTrue(result.get("components") instanceof List<?>);
    }

    @Test
    void wrapsInnerRequestWithCoreForwardMethod() throws Exception {
        Disguise portable = new PortableDisguise();
        byte[] innerResponse = PortableJsonCodec.encode(Map.of("code", 200, "value", "ok"));
        Communication communication = data -> {
            Map<String, Object> relay = PortableJsonCodec.decode(data);
            assertEquals(1, ((Number) relay.get("M")).intValue());
            assertEquals("/inner", relay.get("rUrl"));
            assertArrayEquals(PortableJsonCodec.encode(Map.of("M", 0)), (byte[]) relay.get("body"));
            return PortableJsonCodec.encode(Map.of("code", 200, "respData", innerResponse));
        };

        PhpRpcClient client = new PhpRpcClient(communication,
                List.of(new RequestLayer("/inner", Map.of("X-Layer", "inner"), portable),
                        new RequestLayer("/outer", Map.of(), portable)),
                List.of(new ResponseLayer(portable), new ResponseLayer(portable)));

        Map<String, Object> result = client.ping();
        assertEquals(200, result.get("code"));
        assertEquals("ok", result.get("value"));
    }

    private static final class PortableDisguise extends Disguise {
        @Override
        public byte[] encode(Map<String, Object> params) {
            return PortableJsonCodec.encode(params);
        }

        @Override
        public Map<String, Object> decode(byte[] data) {
            return new LinkedHashMap<>(PortableJsonCodec.decode(data));
        }
    }
}
