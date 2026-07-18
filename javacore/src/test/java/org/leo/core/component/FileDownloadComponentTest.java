package org.leo.core.component;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileDownloadComponentTest {

    @TempDir
    Path tempDir;

    @Test
    void readsBoundedChunksWithoutChangingTheWireContract() throws Exception {
        Path file = tempDir.resolve("chunk.txt");
        byte[] content = "0123456789".getBytes(StandardCharsets.UTF_8);
        Files.write(file, content);

        HashMap first = invoke(file, 0L, 4L);
        assertEquals(100, first.get("code"));
        assertEquals(4, first.get("bytesRead"));
        assertEquals(4L, first.get("nextOffset"));
        assertEquals(Boolean.FALSE, first.get("isComplete"));
        assertArrayEquals("0123".getBytes(StandardCharsets.UTF_8), (byte[]) first.get("data"));

        HashMap last = invoke(file, 4L, 32L);
        assertEquals(200, last.get("code"));
        assertEquals(6, last.get("bytesRead"));
        assertEquals(10L, last.get("nextOffset"));
        assertEquals(Boolean.TRUE, last.get("isComplete"));
        assertArrayEquals("456789".getBytes(StandardCharsets.UTF_8), (byte[]) last.get("data"));
    }

    @Test
    void returnsTheExistingEmptyFileShape() throws Exception {
        Path file = tempDir.resolve("empty.txt");
        Files.createFile(file);

        HashMap result = invoke(file, 0L, 1L);
        assertEquals(200, result.get("code"));
        assertEquals(0, result.get("bytesRead"));
        assertEquals(Boolean.TRUE, result.get("isComplete"));
        assertTrue(((byte[]) result.get("data")).length == 0);
    }

    @Test
    void acceptsStringNumbersFromTransportDecoders() throws Exception {
        Path file = tempDir.resolve("string-numbers.txt");
        Files.write(file, "012345".getBytes(StandardCharsets.UTF_8));

        HashMap result = invoke(file, "2", "3");

        assertEquals(100, result.get("code"));
        assertEquals(3, result.get("bytesRead"));
        assertEquals(5L, result.get("nextOffset"));
        assertArrayEquals("234".getBytes(StandardCharsets.UTF_8), (byte[]) result.get("data"));
    }

    private HashMap invoke(Path file, long offset, long size) throws Exception {
        return invoke(file, Long.valueOf(offset), Long.valueOf(size));
    }

    private HashMap invoke(Path file, Object offset, Object size) throws Exception {
        FileDownloadComponent component = new FileDownloadComponent();
        HashMap params = new HashMap();
        params.put("path", file.toString().getBytes(StandardCharsets.UTF_8));
        params.put("offset", offset);
        params.put("size", size);
        HashMap results = new HashMap();
        setField(component, "params", params);
        setField(component, "results", results);
        component.invoke();
        return results;
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
