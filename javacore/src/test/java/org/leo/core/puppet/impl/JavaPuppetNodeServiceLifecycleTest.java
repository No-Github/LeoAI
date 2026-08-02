package org.leo.core.puppet.impl;

import org.junit.jupiter.api.Test;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;
import org.leo.core.puppet.service.ComponentService;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaPuppetNodeServiceLifecycleTest {

    @Test
    void propagatesStateAcrossEveryRegisteredComponentService() throws Exception {
        JavaPuppetNode node = new JavaPuppetNode();
        node.setCommunication(data -> data);
        node.setHostId("host-before-init");
        node.setMaxReqCount(7);
        node.addLoadedComponent("host-before-init", Set.of("ExistingComponent"));
        node.initService();

        List<ComponentService> services = componentServices(node);
        try {
            assertEquals(28, services.size());
            assertTrue(services.stream().allMatch(service ->
                    "host-before-init".equals(service.getHostId())));
            assertTrue(services.stream().allMatch(service -> service.getMaxReqCount() == 7));
            assertEquals(1, services.stream().map(this::loadRegistry).distinct().count());
            assertTrue(services.stream().allMatch(service ->
                    service.getLoadedComponentNames("host-before-init").contains("ExistingComponent")));

            node.setHostId("host-after-init");
            node.setMaxReqCount(3);
            assertTrue(services.stream().allMatch(service ->
                    "host-after-init".equals(service.getHostId())));
            assertTrue(services.stream().allMatch(service -> service.getMaxReqCount() == 3));

            List<RequestLayer> requests = new ArrayList<RequestLayer>();
            List<ResponseLayer> responses = new ArrayList<ResponseLayer>();
            node.setRequestLayers(requests);
            node.setResponseLayers(responses);
        } finally {
            node.close();
        }
        assertTrue(services.stream().allMatch(service ->
                service.getLoadedComponentNames("host-before-init").isEmpty()));
        assertTrue(node.getLoadedComponents().isEmpty());
    }

    private List<ComponentService> componentServices(JavaPuppetNode node) throws Exception {
        List<ComponentService> services = new ArrayList<ComponentService>();
        for (Field field : JavaPuppetNode.class.getDeclaredFields()) {
            if (!ComponentService.class.isAssignableFrom(field.getType())) continue;
            field.setAccessible(true);
            ComponentService service = (ComponentService) field.get(node);
            if (service != null) services.add(service);
        }
        return services;
    }

    private Object loadRegistry(ComponentService service) {
        try {
            Field field = ComponentService.class.getDeclaredField("componentLoadRegistry");
            field.setAccessible(true);
            return field.get(service);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
