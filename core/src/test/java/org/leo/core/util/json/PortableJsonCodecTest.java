package org.leo.core.util.json;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PortableJsonCodecTest {

    @Test
    void roundTripsNestedBinaryValues() {
        Map<String, Object> decoded = PortableJsonCodec.decode(PortableJsonCodec.encode(Map.of(
                "text", "leo",
                "bytes", new byte[]{0, 1, 2},
                "nested", List.of(Map.of("payload", new byte[]{9, 8}))
        )));

        assertEquals("leo", decoded.get("text"));
        assertArrayEquals(new byte[]{0, 1, 2}, (byte[]) decoded.get("bytes"));
        Map<?, ?> nested = (Map<?, ?>) ((List<?>) decoded.get("nested")).get(0);
        assertArrayEquals(new byte[]{9, 8}, (byte[]) nested.get("payload"));
    }
}
