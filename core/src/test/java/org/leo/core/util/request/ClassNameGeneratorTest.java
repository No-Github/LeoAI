package org.leo.core.util.request;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassNameGeneratorTest {

    @Test
    void generalRuntimeNamesDoNotMixReservedPackagesWithLambdaMarkers() {
        String generated;
        try (GenerationRandom.Scope ignored = GenerationRandom.withSeed(42L)) {
            generated = ClassNameGenerator.generateServletStyleClassName();
        }

        assertFalse(generated.startsWith("java."));
        assertFalse(generated.startsWith("javax."));
        assertFalse(generated.startsWith("sun."));
        assertFalse(generated.contains("$$Lambda$"));
        assertTrue(generated.matches("[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)+\\.[A-Z][A-Za-z0-9]*"));
    }

    @Test
    void componentNamesAreStableAndUseOneCoherentSessionFamily() {
        String session = "host-a|https://example.test/api";
        String basic = ClassNameGenerator.generateComponentClassName(session, "BasicInfoComponent");
        String file = ClassNameGenerator.generateComponentClassName(session, "FileComponent");

        assertEquals(basic,
                ClassNameGenerator.generateComponentClassName(session, "BasicInfoComponent"));
        assertNotEquals(basic, file);
        assertEquals(packageFamily(basic), packageFamily(file));
        assertTrue(basic.matches("[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)+\\.[A-Z][A-Za-z0-9]*"));
        assertFalse(basic.startsWith("java."));
        assertFalse(basic.startsWith("javax."));
        assertFalse(basic.startsWith("sun."));
        assertFalse(basic.contains("$$Lambda$"));
    }

    @Test
    void componentMemberSeedIsStableAndSessionSpecific() {
        long first = ClassNameGenerator.stableSeed("host-a|BasicInfoComponent");
        assertEquals(first, ClassNameGenerator.stableSeed("host-a|BasicInfoComponent"));
        assertNotEquals(first, ClassNameGenerator.stableSeed("host-b|BasicInfoComponent"));
    }

    private String packageFamily(String className) {
        int classSeparator = className.lastIndexOf('.');
        int leafSeparator = className.lastIndexOf('.', classSeparator - 1);
        return className.substring(0, leafSeparator);
    }
}
