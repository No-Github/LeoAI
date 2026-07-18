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
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhpRpcClientTest {

    @Test
    void sendsCoreTestMethodAndKeepsDirectResponseFields() throws Exception {
        Disguise portable = new PortableDisguise();
        Communication communication = data -> {
            Map<String, Object> request = PortableJsonCodec.decode(data);
            assertEquals("PING", request.get("operation"));
            assertEquals(Map.of(), request.get("params"));
            return PortableJsonCodec.encode(Map.of(
                    "requestId", request.get("requestId"),
                    "code", 200,
                    "data", Map.of(
                            "msg", "pong",
                            "hostId", "php-host",
                            "components", List.of("BasicInfoComponent"))
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
        Communication communication = data -> {
            Map<String, Object> relay = PortableJsonCodec.decode(data);
            assertEquals("RELAY", relay.get("operation"));
            Map<?, ?> relayParams = (Map<?, ?>) relay.get("params");
            assertEquals("/inner", relayParams.get("url"));
            Map<String, Object> inner = PortableJsonCodec.decode((byte[]) relayParams.get("body"));
            assertEquals("PING", inner.get("operation"));
            byte[] innerResponse = PortableJsonCodec.encode(Map.of(
                    "requestId", inner.get("requestId"),
                    "code", 200,
                    "data", Map.of("value", "ok")));
            return PortableJsonCodec.encode(Map.of(
                    "requestId", relay.get("requestId"),
                    "code", 200,
                    "data", Map.of("body", innerResponse)));
        };

        PhpRpcClient client = new PhpRpcClient(communication,
                List.of(new RequestLayer("/inner", Map.of("X-Layer", "inner"), portable),
                        new RequestLayer("/outer", Map.of(), portable)),
                List.of(new ResponseLayer(portable), new ResponseLayer(portable)));

        Map<String, Object> result = client.ping();
        assertEquals(200, result.get("code"));
        assertEquals("ok", result.get("value"));
    }

    @Test
    void rejectsResponseWithoutMatchingRequestId() {
        Disguise portable = new PortableDisguise();
        int[] calls = {0};
        Communication communication = data -> {
            calls[0]++;
            Map<String, Object> request = PortableJsonCodec.decode(data);
            return PortableJsonCodec.encode(Map.of("code", 200, "hostId", "invalid"));
        };

        PhpRpcClient client = new PhpRpcClient(communication,
                List.of(new RequestLayer("/", Map.of(), portable)),
                List.of(new ResponseLayer(portable)));

        assertThrows(IllegalStateException.class, client::ping);
        assertEquals(1, calls[0]);
    }

    @Test
    void retriesWithBoundedBackoffAndKeepsRequestIdentity() throws Exception {
        Disguise portable = new PortableDisguise();
        AtomicInteger attempts = new AtomicInteger();
        List<String> requestIds = new ArrayList<>();
        List<Long> delays = new ArrayList<>();
        Communication communication = data -> {
            Map<String, Object> request = PortableJsonCodec.decode(data);
            requestIds.add(String.valueOf(request.get("requestId")));
            if (attempts.incrementAndGet() < 3) throw new java.io.IOException("temporary");
            return PortableJsonCodec.encode(Map.of(
                    "requestId", request.get("requestId"),
                    "code", 200,
                    "data", Map.of("msg", "pong")));
        };

        PhpRpcClient client = new PhpRpcClient(communication,
                List.of(new RequestLayer("/", Map.of(), portable)),
                List.of(new ResponseLayer(portable)));
        client.setMaxReqCount(3);
        client.setRetryBackoff(100, 1_000);
        client.setRetrySleeper(delays::add);

        assertEquals("pong", client.ping().get("msg"));
        assertEquals(3, attempts.get());
        assertEquals(1, requestIds.stream().distinct().count());
        assertEquals(2, delays.size());
        assertTrue(delays.get(0) >= 75 && delays.get(0) <= 125);
        assertTrue(delays.get(1) >= 150 && delays.get(1) <= 250);
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
