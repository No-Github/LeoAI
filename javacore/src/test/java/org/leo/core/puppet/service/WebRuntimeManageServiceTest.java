package org.leo.core.puppet.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WebRuntimeManageServiceTest {

    @Test
    void resolvesVersionedTomcatProfiles() {
        assertEquals("tomcat-6", WebRuntimeManageService.resolveProfileId("Tomcat", "Apache Tomcat/6.0.53"));
        assertEquals("tomcat-7", WebRuntimeManageService.resolveProfileId("Tomcat", "Apache Tomcat/7.0.109"));
        assertEquals("tomcat-8.0", WebRuntimeManageService.resolveProfileId("Tomcat", "Apache Tomcat/8.0.53"));
        assertEquals("tomcat-8.5", WebRuntimeManageService.resolveProfileId("Tomcat", "Apache Tomcat/8.5.100"));
        assertEquals("tomcat-9", WebRuntimeManageService.resolveProfileId("Tomcat", "Apache Tomcat/9.0.89"));
        assertEquals("tomcat-10.0", WebRuntimeManageService.resolveProfileId("Tomcat", "Apache Tomcat/10.0.27"));
        assertEquals("tomcat-10.1", WebRuntimeManageService.resolveProfileId("Tomcat", "Apache Tomcat/10.1.25"));
    }

    @Test
    void exposesTomcatServletAndNamespaceBoundaries() {
        assertProfile("Apache Tomcat/6.0.53", "2.5", "JAVAX", true);
        assertProfile("Apache Tomcat/7.0.109", "3.0", "JAVAX", true);
        assertProfile("Apache Tomcat/8.0.53", "3.1", "JAVAX", true);
        assertProfile("Apache Tomcat/8.5.100", "3.1", "JAVAX", true);
        assertProfile("Apache Tomcat/9.0.89", "4.0", "JAVAX", true);
        assertProfile("Apache Tomcat/10.0.27", "5.0", "JAKARTA", true);
        assertProfile("Apache Tomcat/10.1.25", "6.0", "JAKARTA", true);
        assertProfile("Apache Tomcat/11.0.0", "6.1", "JAKARTA", true);
        assertProfile("Apache Tomcat/12.0.0", "unknown", "JAKARTA", false);
        assertProfile("unknown", "unknown", "UNKNOWN", false);
    }

    @Test
    void selectsTomcatStructuralSubStrategyInsideVersionProfile() {
        WebRuntimeProfileRegistry.RuntimeProfile profile =
                WebRuntimeProfileRegistry.resolve("Tomcat", "8.5.100");
        assertEquals("tomcat-8.5:list-field", WebRuntimeProfileRegistry.strategyId(
                profile, Map.of("listenerStorage", "applicationEventListenersList")));
        assertEquals("tomcat-8.5:objects-field", WebRuntimeProfileRegistry.strategyId(
                profile, Map.of("listenerStorage", "applicationEventListenersObjects")));
    }

    @Test
    void resolvesContainerAdaptersWithoutPretendingGenericMutationSupport() {
        assertEquals("TomcatContainerManageComponent",
                WebRuntimeManageService.resolveContainerComponentName("Tomcat", "9.0.89"));
        assertEquals("WeblogicContainerManageComponent",
                WebRuntimeManageService.resolveContainerComponentName("WebLogic", "14.1.1"));
        assertEquals("GenericServletContainerManageComponent",
                WebRuntimeManageService.resolveContainerComponentName("Jetty", "12.0.10"));
        assertEquals("GenericServletContainerManageComponent",
                WebRuntimeManageService.resolveContainerComponentName("WildFly/JBoss", "30.0.1"));
        assertThrows(IllegalArgumentException.class,
                () -> WebRuntimeManageService.resolveContainerComponentName("Unknown", "unknown"));
    }

    @Test
    void keepsUnverifiedWeblogicVersionsReadOnly() {
        WebRuntimeProfileRegistry.RuntimeProfile supported =
                WebRuntimeProfileRegistry.resolve("WebLogic", "14.1.1");
        assertEquals("weblogic-14c", supported.profileId);
        assertEquals("JAVAX", supported.namespace);
        assertEquals(true, supported.supportsMutation("servlet"));

        WebRuntimeProfileRegistry.RuntimeProfile future =
                WebRuntimeProfileRegistry.resolve("WebLogic", "15.0.0");
        assertEquals("weblogic-unknown", future.profileId);
        assertEquals("UNKNOWN", future.namespace);
        assertEquals(false, future.supportsMutation("servlet"));
    }

    @Test
    void onlyMapsFrameworksWithImplementedManagementPaths() {
        assertEquals("SpringFrameworkManageComponent",
                WebRuntimeManageService.resolveFrameworkComponentName("Spring Boot (MVC)"));
        assertEquals("SpringFrameworkManageComponent",
                WebRuntimeManageService.resolveFrameworkComponentName("WebFlux"));
        assertEquals("JavaWebFrameworkManageComponent",
                WebRuntimeManageService.resolveFrameworkComponentName("Struts2"));
        assertEquals("JavaWebFrameworkManageComponent",
                WebRuntimeManageService.resolveFrameworkComponentName("Jakarta Faces"));
        assertEquals("JavaWebFrameworkManageComponent",
                WebRuntimeManageService.resolveFrameworkComponentName("JAX-RS"));
        assertNull(WebRuntimeManageService.resolveFrameworkComponentName("Unknown"));
    }

    private void assertProfile(String version, String servletApi, String namespace, boolean mutable) {
        WebRuntimeProfileRegistry.RuntimeProfile profile =
                WebRuntimeProfileRegistry.resolve("Tomcat", version);
        assertEquals(servletApi, profile.servletApiVersion);
        assertEquals(namespace, profile.namespace);
        assertEquals(mutable, profile.supportsMutation("servlet"));
        assertEquals(mutable, profile.supportsMutation("filter"));
        assertEquals(mutable, profile.supportsMutation("listener"));
        assertEquals(mutable, profile.supportsMutation("valve"));
    }
}
