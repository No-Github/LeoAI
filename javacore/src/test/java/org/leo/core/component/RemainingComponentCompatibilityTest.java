package org.leo.core.component;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leo.core.util.javassist.CloneWithJavassist;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemainingComponentCompatibilityTest {

    @TempDir
    Path tempDir;

    @Test
    void transformedPayloadsRemainRunnableAfterMethodRandomization() throws Exception {
        assertTransformedRunnable("ClipboardComponent");
        assertTransformedRunnable("DecompressComponent");
        assertTransformedRunnable("FileUploadComponent");
        assertTransformedRunnable("ScreenComponent");
        assertTransformedRunnable("ResourceComponent");
    }

    @Test
    void clipboardAcceptsByteActionAndRejectsOversizedContentBeforeOsAccess() throws Exception {
        Map<String, Object> invalidAction = invoke(new ClipboardComponent(), params(
                "action", utf8("invalid-action")));
        assertEquals(400, code(invalidAction));

        Map<String, Object> oversized = invoke(new ClipboardComponent(), params(
                "action", utf8("write"), "content", new byte[1024 * 1024 + 1]));
        assertEquals(413, code(oversized));
    }

    @Test
    void clipboardPassesWriteContentThroughProcessStdin() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(isWindows());
        ClipboardComponent component = new ClipboardComponent();
        Method exec = ClipboardComponent.class.getDeclaredMethod(
                "execCommand", String.class, byte[].class);
        exec.setAccessible(true);
        byte[] content = utf8("$HOME 'quoted' \"double\"");

        String output = (String) exec.invoke(component, "cat", content);

        assertEquals(new String(content, StandardCharsets.UTF_8), output);
        assertFalse((Boolean) field(component, "execCmdMode"));
        assertTrue((Boolean) field(component, "execCmdDone"));
    }

    @Test
    void decompressAcceptsMixedStringAndByteParameters() throws Exception {
        Path archive = tempDir.resolve("sample.zip");
        writeZip(archive, "nested/value.txt", utf8("zip-ok"));
        Path output = tempDir.resolve("output");
        Files.createDirectories(output.resolve("nested"));
        Files.write(output.resolve("nested/value.txt"), utf8("old-value"));

        Map<String, Object> response = invoke(new DecompressComponent(), params(
                "src", archive.toString(), "des", utf8(output.toString()), "format", utf8("zip")));

        assertEquals(200, code(response));
        assertEquals("zip", response.get("format"));
        assertEquals("zip-ok", Files.readString(output.resolve("nested/value.txt")));
    }

    @Test
    void failedGzipDoesNotDestroyExistingOutput() throws Exception {
        Path archive = tempDir.resolve("invalid.gz");
        Files.write(archive, utf8("not-gzip"));
        Path output = tempDir.resolve("existing.txt");
        Files.write(output, utf8("keep-me"));

        Map<String, Object> response = invoke(new DecompressComponent(), params(
                "src", archive.toString(), "des", output.toString(), "format", "gzip"));

        assertEquals(500, code(response));
        assertEquals("keep-me", Files.readString(output));
    }

    @Test
    void invalidArchiveFormatReturnsClientError() throws Exception {
        Map<String, Object> response = invoke(new DecompressComponent(), params(
                "src", "archive.bin", "des", tempDir.toString(), "format", "unknown"));
        assertEquals(400, code(response));
    }

    @Test
    void uploadAcceptsStringPathAndByteOffset() throws Exception {
        Path output = tempDir.resolve("upload.bin");
        Map<String, Object> first = invoke(new FileUploadComponent(), params(
                "path", output.toString(), "offset", utf8("0"), "data", utf8("first")));
        Map<String, Object> second = invoke(new FileUploadComponent(), params(
                "path", utf8(output.toString()), "offset", "5", "data", utf8("-second")));

        assertEquals(200, code(first));
        assertEquals(200, code(second));
        assertEquals(12L, second.get("nextOffset"));
        assertEquals(12L, second.get("fileLength"));
        assertEquals("first-second", Files.readString(output));
    }

    @Test
    void uploadRejectsInvalidOffsetAndOversizedChunk() throws Exception {
        Map<String, Object> invalidOffset = invoke(new FileUploadComponent(), params(
                "path", tempDir.resolve("invalid.bin").toString(),
                "offset", "not-number", "data", new byte[0]));
        assertEquals(400, code(invalidOffset));

        Map<String, Object> oversized = invoke(new FileUploadComponent(), params(
                "path", tempDir.resolve("large.bin").toString(),
                "offset", 0, "data", new byte[1024 * 1024 + 1]));
        assertEquals(413, code(oversized));
    }

    @Test
    void screenEncodesArgbInputAsJpegAndParsesByteParameters() throws Exception {
        ScreenComponent component = new ScreenComponent();
        setField(component, "params", params("format", utf8("PNG"),
                "quality", utf8("75"), "delay", utf8("250")));

        Method stringParam = ScreenComponent.class.getDeclaredMethod("getStringParam", String.class);
        Method qualityParam = ScreenComponent.class.getDeclaredMethod(
                "getFloatPercentParam", String.class, float.class);
        Method delayParam = ScreenComponent.class.getDeclaredMethod("getIntParam", String.class, int.class);
        stringParam.setAccessible(true);
        qualityParam.setAccessible(true);
        delayParam.setAccessible(true);
        assertEquals("PNG", stringParam.invoke(component, "format"));
        assertEquals(0.75f, (Float) qualityParam.invoke(component, "quality", 0.8f), 0.0001f);
        assertEquals(250, delayParam.invoke(component, "delay", 100));

        BufferedImage source = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, Color.RED.getRGB());
        Method compress = ScreenComponent.class.getDeclaredMethod(
                "compressImage", BufferedImage.class, String.class, float.class);
        compress.setAccessible(true);
        byte[] jpeg = (byte[]) compress.invoke(component, source, "jpg", 0.8f);

        assertTrue(jpeg.length > 0);
        assertNotNull(ImageIO.read(new ByteArrayInputStream(jpeg)));
    }

    @Test
    void resourceAcceptsLeadingSlashBytesAndResetsOversizeState() throws Exception {
        ResourceComponent component = new ResourceComponent();
        Map<String, Object> found = invoke(component, params(
                "resourcePath", utf8("/component/ResourceComponent.payload")));

        assertEquals(200, code(found));
        assertArrayEquals((byte[]) found.get("bytecode"), (byte[]) found.get("data"));
        assertTrue(((Number) found.get("size")).intValue() > 0);

        setField(component, "resourceTooLarge", true);
        Map<String, Object> missing = invoke(component, params(
                "resourcePath", "component/missing-resource.bin"));
        assertEquals(404, code(missing));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invoke(Object component, HashMap<String, Object> params) throws Exception {
        HashMap<String, Object> results = new HashMap<>();
        setField(component, "params", params);
        setField(component, "results", results);
        component.getClass().getDeclaredMethod("invoke").invoke(component);
        return results;
    }

    private HashMap<String, Object> params(Object... values) {
        HashMap<String, Object> params = new HashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            params.put((String) values[index], values[index + 1]);
        }
        return params;
    }

    private int code(Map<String, Object> response) {
        return ((Number) response.get("code")).intValue();
    }

    private byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows");
    }

    private void writeZip(Path path, String entryName, byte[] data) throws Exception {
        ZipOutputStream output = new ZipOutputStream(new FileOutputStream(path.toFile()));
        try {
            output.putNextEntry(new ZipEntry(entryName));
            output.write(data);
            output.closeEntry();
        } finally {
            output.close();
        }
    }

    private Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private void assertTransformedRunnable(String componentId) throws Exception {
        String className = "org.leo.generated." + componentId + System.nanoTime();
        byte[] bytecode = CloneWithJavassist.cloneClass(componentId, className);
        Class<?> transformed = new BytecodeLoader().define(className, bytecode);
        assertTrue(Runnable.class.isAssignableFrom(transformed));
        assertTrue(transformed.getDeclaredConstructor().newInstance() instanceof Runnable);
    }

    private static final class BytecodeLoader extends ClassLoader {
        private Class<?> define(String name, byte[] bytecode) {
            return defineClass(name, bytecode, 0, bytecode.length);
        }
    }
}
