package org.leo.core.generator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Generated script plus metadata that is safe to expose to UI and AI surfaces. */
public final class GeneratedArtifact {

    private final String content;
    private final String fileExtension;
    private final String mediaType;
    private final Map<String, Object> metadata;
    private final List<String> warnings;

    public GeneratedArtifact(String content, String fileExtension, String mediaType,
                             Map<String, Object> metadata, List<String> warnings) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("generated content不能为空");
        }
        this.content = content;
        this.fileExtension = fileExtension;
        this.mediaType = mediaType;
        this.metadata = metadata == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        this.warnings = warnings == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(warnings));
    }

    public String getContent() { return content; }
    public String getFileExtension() { return fileExtension; }
    public String getMediaType() { return mediaType; }
    public Map<String, Object> getMetadata() { return metadata; }
    public List<String> getWarnings() { return warnings; }
}
