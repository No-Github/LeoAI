package org.leo.jmg;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.Disguise;
import org.leo.jmg.mem.packer.ClassPackerConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ShellGeneratorConfigTest {

    @Test
    void acceptsTrimmedCaseInsensitiveProtocol() {
        ShellGeneratorConfig config = builder()
                .protocol("  HTTPCHUNK  ")
                .build();

        assertEquals("httpchunk", config.getProtocol());
    }

    @Test
    void rejectsInvalidHttpStatusCode() {
        assertThrows(IllegalArgumentException.class, () -> builder().respCode(99));
        assertThrows(IllegalArgumentException.class, () -> builder().respCode(600));
        assertEquals(599, builder().respCode(599).build().getRespCode());
    }

    @Test
    void snapshotsObfuscationStepsAtConfigurationBoundary() {
        List<String> source = new ArrayList<String>(Arrays.asList("CHUNK_PAYLOAD"));

        ShellGeneratorConfig config = builder().jspObfuscationSteps(source).build();
        source.add("SPLIT_STRING_LITERALS");

        assertEquals(Arrays.asList("CHUNK_PAYLOAD"), config.getJspObfuscationSteps());
        assertThrows(UnsupportedOperationException.class,
                () -> config.getJspObfuscationSteps().add("GHOST_BITS_ENCODE"));
    }

    @Test
    void classPackerConfigAlsoSnapshotsSteps() {
        List<String> source = new ArrayList<String>(Arrays.asList("CHUNK_PAYLOAD"));
        ClassPackerConfig config = new ClassPackerConfig();

        config.setJspObfuscationSteps(source);
        source.clear();

        assertEquals(Arrays.asList("CHUNK_PAYLOAD"), config.getJspObfuscationSteps());
        assertThrows(UnsupportedOperationException.class,
                () -> config.getJspObfuscationSteps().clear());
    }

    @Test
    void parsesExplicitTargetJavaVersionsAndDefaultsToAuto() {
        assertEquals(TargetJavaVersion.AUTO, builder().build().getTargetJavaVersion());
        assertEquals(TargetJavaVersion.JDK_6,
                builder().targetJavaVersion("1.6").build().getTargetJavaVersion());
        assertEquals(TargetJavaVersion.JDK_8,
                builder().targetJavaVersion("jdk_8").build().getTargetJavaVersion());
        assertEquals(TargetJavaVersion.JDK_9_PLUS,
                builder().targetJavaVersion("11").build().getTargetJavaVersion());
        assertEquals(TargetJavaVersion.JDK_17_PLUS,
                builder().targetJavaVersion("17+").build().getTargetJavaVersion());
        assertThrows(IllegalArgumentException.class,
                () -> builder().targetJavaVersion("5"));
    }

    @Test
    void parsesServletNamespacesAndKeepsLegacyDefault() {
        assertEquals(ServletNamespace.AUTO, builder().build().getServletNamespace());
        assertEquals(ServletNamespace.JAVAX, builder().build().getEffectiveServletNamespace());
        assertEquals(ServletNamespace.JAKARTA,
                builder().servletNamespace("jakarta").build().getEffectiveServletNamespace());
        assertThrows(IllegalArgumentException.class,
                () -> builder().servletNamespace("unsupported"));
        assertThrows(IllegalArgumentException.class,
                () -> builder().servletNamespace("jakarta").targetJavaVersion("7").build().validate());
        assertEquals(1, builder().servletNamespace("jakarta").build()
                .getCompatibilityWarnings().size());
    }

    private ShellGeneratorConfig.Builder builder() {
        return ShellGeneratorConfig.builder(new Disguise(), new Disguise());
    }
}
