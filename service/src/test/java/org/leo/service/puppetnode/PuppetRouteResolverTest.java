package org.leo.service.puppetnode;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.Puppet;
import org.leo.service.PuppetService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PuppetRouteResolverTest {

    @Test
    void resolvesOneImmutableChildToTransportRoute() {
        PuppetService service = mock(PuppetService.class);
        Puppet child = puppet("child", "middle");
        Puppet middle = puppet("middle", "transport");
        Puppet transport = puppet("transport", "root");
        when(service.findPuppetById("middle")).thenReturn(middle);
        when(service.findPuppetById("transport")).thenReturn(transport);

        PuppetRouteResolver.Route route = new PuppetRouteResolver(service).resolve(child);

        assertSame(child, route.requested());
        assertSame(transport, route.transport());
        assertEquals(java.util.List.of(child, middle, transport), route.chain());
        assertThrows(UnsupportedOperationException.class, () -> route.chain().clear());
    }

    @Test
    void rejectsMissingParentAndParentCycle() {
        PuppetService service = mock(PuppetService.class);
        Puppet child = puppet("child", "missing");
        assertThrows(IllegalArgumentException.class,
                () -> new PuppetRouteResolver(service).resolve(child));

        Puppet first = puppet("first", "second");
        Puppet second = puppet("second", "first");
        when(service.findPuppetById("second")).thenReturn(second);
        when(service.findPuppetById("first")).thenReturn(first);
        assertThrows(IllegalArgumentException.class,
                () -> new PuppetRouteResolver(service).resolve(first));
    }

    @Test
    void connectionPlanResolvesTheParentChainOnlyOnce() throws Exception {
        PuppetService service = mock(PuppetService.class);
        Puppet child = puppet("child", "transport");
        Puppet transport = puppet("transport", "root");
        transport.setProtocol("http");
        transport.setConnLink("http://127.0.0.1/runtime");
        when(service.findPuppetById("transport")).thenReturn(transport);

        PuppetNodeFactory factory = new PuppetNodeFactory(service, java.util.List.of());
        var plan = factory.createConnectionPlan(child);

        assertEquals(0, plan.getTransportLayers().getRequestLayers().size());
        assertEquals(0, plan.getTransportLayers().getResponseLayers().size());
        verify(service, times(1)).findPuppetById("transport");
    }

    private Puppet puppet(String id, String parentId) {
        Puppet puppet = new Puppet();
        puppet.setPuppetId(id);
        puppet.setParentPuppetId(parentId);
        return puppet;
    }
}
