package org.leo.core.repository.session;

import org.leo.core.util.json.JsonUtil;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small shared store for atomic UTF-8 text and JSON replacement writes. */
@Component
public class AtomicFileStore {

    public File writeJson(File target, Map<String, Object> data) throws Exception {
        return writeText(target, JsonUtil.toJsonString(data));
    }

    public File writeText(File target, String content) throws Exception {
        if (target == null) throw new IllegalArgumentException("目标文件不能为空");
        Path path = target.toPath().toAbsolutePath().normalize();
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, "." + target.getName() + ".", ".tmp");
        try {
            Files.writeString(temp, content == null ? "" : content, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
            return path.toFile();
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> readJsonMap(File file) {
        try {
            if (file == null || !file.exists() || file.length() == 0) return null;
            return (Map<String, Object>) JsonUtil.fromJsonString(
                    Files.readString(file.toPath(), StandardCharsets.UTF_8), LinkedHashMap.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    public String readText(File file) {
        try {
            if (file == null || !file.exists() || file.length() == 0) return null;
            String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            return content == null || content.isBlank() ? null : content.strip();
        } catch (Exception ignored) {
            return null;
        }
    }
}
