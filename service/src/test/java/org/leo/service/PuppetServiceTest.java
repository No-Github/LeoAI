package org.leo.service;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.Puppet;
import org.leo.dao.mapper.PuppetMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PuppetServiceTest {

    @Test
    void rejectsMaximumRequestCountOutsideThePublicContract() {
        PuppetMapper mapper = mock(PuppetMapper.class);
        PuppetService service = new PuppetService(mapper);
        Puppet puppet = new Puppet();
        puppet.setMaxReqCount(Puppet.MAX_REQUEST_COUNT + 1);

        assertThrows(IllegalArgumentException.class, () -> service.insertPuppet(puppet));
        verifyNoInteractions(mapper);
    }

    @Test
    void deletesCompletePuppetSubtreeFromLeavesToRoot() {
        PuppetMapper mapper = mock(PuppetMapper.class);
        PuppetService service = new PuppetService(mapper);
        Puppet root = puppet("root-node");
        Puppet child = puppet("child-node");
        Puppet grandchild = puppet("grandchild-node");
        when(mapper.findPuppetById("root-node")).thenReturn(root);
        when(mapper.findPuppetByParentPuppetId("root-node")).thenReturn(List.of(child));
        when(mapper.findPuppetByParentPuppetId("child-node")).thenReturn(List.of(grandchild));
        when(mapper.findPuppetByParentPuppetId("grandchild-node")).thenReturn(List.of());
        when(mapper.deletePuppetById("grandchild-node")).thenReturn(true);
        when(mapper.deletePuppetById("child-node")).thenReturn(true);
        when(mapper.deletePuppetById("root-node")).thenReturn(true);

        assertTrue(service.deletePuppetById("root-node"));

        var order = inOrder(mapper);
        order.verify(mapper).deletePuppetById("grandchild-node");
        order.verify(mapper).deletePuppetById("child-node");
        order.verify(mapper).deletePuppetById("root-node");
    }

    @Test
    void rollsBackContractWhenAnySubtreeNodeCannotBeDeleted() {
        PuppetMapper mapper = mock(PuppetMapper.class);
        PuppetService service = new PuppetService(mapper);
        Puppet root = puppet("root-node");
        Puppet child = puppet("child-node");
        when(mapper.findPuppetById("root-node")).thenReturn(root);
        when(mapper.findPuppetByParentPuppetId("root-node")).thenReturn(List.of(child));
        when(mapper.findPuppetByParentPuppetId("child-node")).thenReturn(List.of());
        when(mapper.deletePuppetById("child-node")).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> service.deletePuppetById("root-node"));
    }

    private static Puppet puppet(String puppetId) {
        Puppet puppet = new Puppet();
        puppet.setPuppetId(puppetId);
        return puppet;
    }
}
