package org.leo.jmg.registry;

import org.junit.jupiter.api.Test;
import org.leo.jmg.mem.packer.Packer;
import org.leo.jmg.TargetJavaVersion;
import org.leo.jmg.mem.packer.PackerMeta;
import org.leo.jmg.mem.packer.PackerRegistry;
import org.leo.jmg.mem.packer.PackerCompatibilityResult;
import org.leo.jmg.mem.packer.base64.DefaultBase64Packer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

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

    @Test
    void validatesExplicitTargetJavaCompatibility() {
        PackerRegistry.validateCompatibility(
                "DefaultBase64", TargetJavaVersion.JDK_6, false);
        PackerRegistry.validateCompatibility(
                "SpELSpringGzipJDK17", TargetJavaVersion.JDK_17_PLUS, false);

        IllegalArgumentException versionError = assertThrows(IllegalArgumentException.class,
                () -> PackerRegistry.validateCompatibility(
                        "SpELSpringGzipJDK17", TargetJavaVersion.JDK_8, false));
        assertTrue(versionError.getMessage().contains("最低要求 JDK 17"));

        IllegalArgumentException moduleError = assertThrows(IllegalArgumentException.class,
                () -> PackerRegistry.validateCompatibility(
                        "DefineClassJSP", TargetJavaVersion.JDK_8, true));
        assertTrue(moduleError.getMessage().contains("仅适用于 JDK 9+"));
    }

    @Test
    void exposesCompatibilityMetadata() {
        Map<String, Map<String, Object>> compatibility = PackerRegistry.getCompatibilityMap();
        assertEquals(6, compatibility.get("DefaultBase64").get("minTargetJava"));
        assertEquals(17, compatibility.get("SpELSpringGzipJDK17").get("minTargetJava"));
        assertTrue(((List<?>) compatibility.get("DefaultScriptEngine")
                .get("requiredCapabilities")).contains("javascript-engine"));
        assertTrue(((List<?>) compatibility.get("JXPathScriptEngine")
                .get("requiredCapabilities")).contains("javascript-engine"));
        assertEquals(java.util.Arrays.asList("http", "httpchunk", "websocket"),
                compatibility.get("DefaultBase64").get("supportedProtocols"));
    }

    @Test
    void warnsWhenJavaScriptEngineCannotBeAssumed() {
        PackerCompatibilityResult jdk17 = PackerRegistry.evaluateCompatibility(
                "SpELScriptEngine", TargetJavaVersion.JDK_17_PLUS, false);
        assertTrue(jdk17.isSupported());
        assertTrue(jdk17.getWarnings().get(0).contains("JDK 15+"));

        PackerCompatibilityResult auto = PackerRegistry.evaluateCompatibility(
                "JXPathScriptEngine", TargetJavaVersion.AUTO, false);
        assertTrue(auto.isSupported());
        assertTrue(auto.getWarnings().get(0).contains("auto"));

        assertTrue(PackerRegistry.evaluateCompatibility(
                "DefaultBase64", TargetJavaVersion.JDK_17_PLUS, false).getWarnings().isEmpty());
    }

    @Test
    void lazilyCreatesAndCachesPackerInstance() {
        LazyPacker.constructions = 0;
        PackerRegistry.registerClass(LazyPacker.class);

        assertEquals("uninitialized", availability("LazyTest").get("status"));
        Packer first = PackerRegistry.get("LazyTest");
        Packer second = PackerRegistry.get("lazytest");

        assertSame(first, second);
        assertEquals(1, LazyPacker.constructions);
        assertEquals("available", availability("LazyTest").get("status"));
    }

    @Test
    void cachesInitializationFailureUntilExplicitInstanceRegistration() {
        FailingPacker.constructions = 0;
        FailingPacker.failConstruction = true;
        PackerRegistry.registerClass(FailingPacker.class);

        IllegalStateException first = assertThrows(IllegalStateException.class,
                () -> PackerRegistry.get("FailingTest"));
        IllegalStateException second = assertThrows(IllegalStateException.class,
                () -> PackerRegistry.get("FailingTest"));

        assertTrue(first.getMessage().contains("broken constructor"));
        assertTrue(second.getMessage().contains("broken constructor"));
        assertEquals(1, FailingPacker.constructions);
        assertEquals("failed", availability("FailingTest").get("status"));
        assertTrue(String.valueOf(availability("FailingTest").get("failureReason"))
                .contains("broken constructor"));
        assertTrue(!PackerRegistry.evaluateCompatibility(
                "FailingTest", TargetJavaVersion.AUTO, false).isSupported());

        FailingPacker.failConstruction = false;
        PackerRegistry.register(new FailingPacker());
        assertEquals("available", availability("FailingTest").get("status"));
    }

    @Test
    void concurrentLookupCreatesOnlyOneInstance() throws Exception {
        ConcurrentLazyPacker.constructions = 0;
        PackerRegistry.registerClass(ConcurrentLazyPacker.class);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<Packer>> futures = new ArrayList<Future<Packer>>();
            for (int i = 0; i < 24; i++) {
                futures.add(executor.submit(new Callable<Packer>() {
                    @Override
                    public Packer call() {
                        return PackerRegistry.get("ConcurrentLazyTest");
                    }
                }));
            }
            Packer expected = futures.get(0).get();
            for (Future<Packer> future : futures) {
                assertSame(expected, future.get());
            }
            assertEquals(1, ConcurrentLazyPacker.constructions);
        } finally {
            executor.shutdownNow();
        }
    }

    private Map<String, Object> availability(String name) {
        return PackerRegistry.getAvailabilityMap().get(name);
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

    @PackerMeta(name = "LazyTest")
    public static final class LazyPacker implements Packer {
        private static int constructions;

        public LazyPacker() {
            constructions++;
        }

        @Override
        public String pack(org.leo.jmg.mem.packer.ClassPackerConfig config) {
            return "lazy";
        }
    }

    @PackerMeta(name = "FailingTest")
    public static final class FailingPacker implements Packer {
        private static int constructions;
        private static boolean failConstruction = true;

        public FailingPacker() {
            constructions++;
            if (failConstruction) {
                throw new IllegalStateException("broken constructor");
            }
        }

        @Override
        public String pack(org.leo.jmg.mem.packer.ClassPackerConfig config) {
            return "recovered";
        }
    }

    @PackerMeta(name = "ConcurrentLazyTest")
    public static final class ConcurrentLazyPacker implements Packer {
        private static int constructions;

        public ConcurrentLazyPacker() {
            constructions++;
        }

        @Override
        public String pack(org.leo.jmg.mem.packer.ClassPackerConfig config) {
            return "concurrent";
        }
    }
}
