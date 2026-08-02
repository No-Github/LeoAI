package org.leo.core.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PuppetTest {

    @Test
    void maximumRequestCountIncludesTheInitialRequest() {
        Puppet puppet = new Puppet();

        assertEquals(1, puppet.getMaxReqCount());

        puppet.setMaxReqCount(3);
        assertEquals(3, puppet.getMaxReqCount());
        assertEquals(3, Puppet.requireValidMaxRequestCount(puppet.getMaxReqCount()));
    }

    @Test
    void rejectsMissingOrOutOfRangeMaximumRequestCounts() {
        assertThrows(IllegalArgumentException.class,
                () -> Puppet.requireValidMaxRequestCount(null));
        assertThrows(IllegalArgumentException.class,
                () -> Puppet.requireValidMaxRequestCount(0));
        assertThrows(IllegalArgumentException.class,
                () -> Puppet.requireValidMaxRequestCount(-1));
        assertThrows(IllegalArgumentException.class,
                () -> Puppet.requireValidMaxRequestCount(11));
    }
}
