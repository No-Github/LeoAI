package org.leo.jmg.catalog;

import org.junit.jupiter.api.Test;
import org.leo.jmg.TransportProtocol;

import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratorCatalogTest {

    @Test
    void everyDescriptorIsUniqueAndRoundTripsThroughResolver() {
        Set<String> keys = new HashSet<String>();

        for (InjectorDescriptor descriptor : GeneratorCatalog.getAllDescriptors()) {
            String key = descriptor.getServerType().getValue() + "|"
                    + descriptor.getProtocol().getValue() + "|"
                    + descriptor.getInjectorName();

            assertTrue(keys.add(key), "目录项不能重复: " + key);
            assertSame(descriptor, GeneratorCatalog.resolve(
                    descriptor.getServerType().getValue(),
                    descriptor.getInjectorName(),
                    descriptor.getProtocol().getValue()));
        }
        assertFalse(keys.isEmpty());
    }

    @Test
    void everyDescriptorReferencesExistingTemplateBytes() throws Exception {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();

        for (InjectorDescriptor descriptor : GeneratorCatalog.getAllDescriptors()) {
            String shellResource =
                    descriptor.getShellTemplateName().replace('.', '/') + ".class";
            String injectorResource =
                    descriptor.getInjectorTemplateName().replace('.', '/') + ".class";

            assertResourceExists(loader, shellResource);
            assertResourceExists(loader, injectorResource);
        }
    }

    @Test
    void protocolMetadataComesFromResolvableCatalogEntries() {
        Map<String, Map<String, List<String>>> matrix =
                GeneratorCatalog.getProtocolInjectorMap();

        for (Map.Entry<String, Map<String, List<String>>> protocolEntry : matrix.entrySet()) {
            for (Map.Entry<String, List<String>> serverEntry
                    : protocolEntry.getValue().entrySet()) {
                for (String injectorName : serverEntry.getValue()) {
                    assertNotNull(GeneratorCatalog.resolve(
                            serverEntry.getKey(), injectorName, protocolEntry.getKey()));
                }
            }
        }
        assertEquals(TransportProtocol.values().length, matrix.size());
        assertThrows(UnsupportedOperationException.class,
                () -> matrix.put("invalid", null));
    }

    @Test
    void httpChunkNeverFallsBackToOrdinaryHttpTemplate() {
        InjectorDescriptor filter =
                GeneratorCatalog.resolve("Tomcat", "FilterInjector", "httpchunk");
        InjectorDescriptor valve =
                GeneratorCatalog.resolve("Tomcat", "ValveInjector", "httpchunk");

        assertNotNull(filter);
        assertNotNull(valve);
        assertEquals("org.leo.jmg.mem.shell.http.LeoFilterChunkTpl",
                filter.getShellTemplateName());
        assertEquals("org.leo.jmg.mem.shell.http.LeoValveChunkTpl",
                valve.getShellTemplateName());
        assertNull(GeneratorCatalog.resolve(
                "InforSuite", "ListenerInjector", "httpchunk"));
        assertNull(GeneratorCatalog.resolve(
                "SpringWebMVC", "InterceptorInjector", "httpchunk"));
    }

    @Test
    void rejectsRemovedLegacyChunkAliases() {
        assertNull(GeneratorCatalog.resolve(
                "Tomcat", "FilterInjector-HTTPCHUNK", "httpchunk"));
        assertNull(GeneratorCatalog.resolve(
                "Tomcat", "FilterInjector-HTTPCHUNK", "http"));
    }

    private static void assertResourceExists(ClassLoader loader, String resource) throws Exception {
        try (InputStream input = loader.getResourceAsStream(resource)) {
            assertNotNull(input, "目录引用的模板不存在: " + resource);
        }
    }
}
