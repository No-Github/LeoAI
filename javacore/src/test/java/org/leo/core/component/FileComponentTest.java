package org.leo.core.component;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileComponentTest {

    @TempDir
    Path tempDir;

    @Test
    void listsFilesWhenActionIsDeserializedAsString() throws Exception {
        Files.writeString(tempDir.resolve("sample.txt"), "sample", StandardCharsets.UTF_8);

        HashMap results = invoke("1");

        assertEquals(200, results.get("code"));
        assertEquals(1, results.get("count"));
    }

    @Test
    void listsFilesWhenActionIsDeserializedAsUtf8Bytes() throws Exception {
        HashMap results = invoke("1".getBytes(StandardCharsets.UTF_8));

        assertEquals(200, results.get("code"));
        assertEquals(0, results.get("count"));
    }

    private HashMap invoke(Object action) throws Exception {
        FileComponent component = new FileComponent();
        HashMap params = new HashMap();
        params.put("action", action);
        params.put("path", tempDir.toString().getBytes(StandardCharsets.UTF_8));
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
