package org.leo.core.repository.session;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AtomicFileStoreTest {

    @Test
    void replacesTextAtomicallyAndReadsJson() throws Exception {
        AtomicFileStore store = new AtomicFileStore();
        Path root = Files.createTempDirectory("leo-atomic-store-");
        Path target = root.resolve("nested/state.json");

        store.writeText(target.toFile(), "first");
        store.writeText(target.toFile(), "second");
        assertEquals("second", Files.readString(target));

        store.writeJson(target.toFile(), Map.of("hostId", "host-a"));
        assertEquals("host-a", store.readJsonMap(target.toFile()).get("hostId"));
        assertNull(store.readText(root.resolve("missing.txt").toFile()));
    }
}
