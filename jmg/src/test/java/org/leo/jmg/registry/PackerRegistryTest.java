package org.leo.jmg.registry;

import org.junit.jupiter.api.Test;
import org.leo.jmg.mem.packer.Packer;
import org.leo.jmg.mem.packer.PackerMeta;
import org.leo.jmg.mem.packer.PackerRegistry;
import org.leo.jmg.mem.packer.base64.DefaultBase64Packer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackerRegistryTest {

    @Test
    void discoversPackersAndReturnsEachNameOnce() {
        List<String> names = PackerRegistry.getAllNames();
        Set<String> normalized = new HashSet<String>();
        for (String name : names) {
            assertTrue(normalized.add(name.toLowerCase(Locale.ROOT)), "重复 Packer 名称: " + name);
        }

        assertInstanceOf(DefaultBase64Packer.class, PackerRegistry.get("  DEFAULTBASE64  "));
    }

    @Test
    void lookupIsIndependentOfDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            assertTrue(PackerRegistry.contains("BIGINTEGER"));
            assertEquals("BigInteger", PackerRegistry.getMeta("BIGINTEGER").name());
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void duplicateScanOfSameImplementationIsIdempotent() {
        int before = occurrencesOf("DefaultBase64");

        PackerRegistry.register(new DefaultBase64Packer());

        assertEquals(before, occurrencesOf("DefaultBase64"));
    }

    @Test
    void conflictingImplementationsCannotReuseARegisteredName() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> PackerRegistry.register(new ConflictingPacker()));

        assertTrue(error.getMessage().contains("DefaultBase64"));
        assertInstanceOf(DefaultBase64Packer.class, PackerRegistry.get("DefaultBase64"));
    }

    @Test
    void hierarchyUsesStableOrderWithinGroup() {
        Map<String, Object> hierarchy = PackerRegistry.getHierarchy();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) hierarchy.get("groups");

        Map<String, Object> base64 = null;
        for (Map<String, Object> group : groups) {
            if ("Base64".equals(group.get("groupName"))) {
                base64 = group;
                break;
            }
        }

        assertTrue(base64 != null, "缺少 Base64 分组");
        @SuppressWarnings("unchecked")
        List<String> packers = (List<String>) base64.get("packers");
        assertEquals(java.util.Arrays.asList("DefaultBase64", "Base64URLEncoded", "GzipBase64"), packers);
    }

    private int occurrencesOf(String expectedName) {
        int count = 0;
        for (String name : new ArrayList<String>(PackerRegistry.getAllNames())) {
            if (expectedName.equalsIgnoreCase(name)) {
                count++;
            }
        }
        return count;
    }

    @PackerMeta(name = "DefaultBase64")
    private static final class ConflictingPacker implements Packer {
    }
}
