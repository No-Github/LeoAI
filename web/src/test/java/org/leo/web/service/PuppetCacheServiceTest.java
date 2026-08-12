package org.leo.web.service;

import org.junit.jupiter.api.Test;
import org.leo.core.repository.session.PuppetHostCacheRepository;
import org.leo.core.repository.session.AtomicFileStore;
import org.leo.web.exception.ApiException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PuppetCacheServiceTest {

    @Test
    void automaticallySelectsTheOnlyCachedHost() {
        PuppetCacheService service = serviceWithHosts(List.of("host-a"));
        assertEquals("host-a", service.requireSelectedHostId("user", "puppet", null));
    }

    @Test
    void requiresSelectionWhenMultipleHostsAreCached() {
        PuppetCacheService service = serviceWithHosts(List.of("host-a", "host-b"));
        assertThrows(ApiException.class,
                () -> service.requireSelectedHostId("user", "puppet", null));
        assertEquals("host-b", service.requireSelectedHostId("user", "puppet", "host-b"));
    }

    private PuppetCacheService serviceWithHosts(List<String> hosts) {
        return new PuppetCacheService(new PuppetHostCacheRepository(new AtomicFileStore())) {
            @Override
            public List<String> hostIds(String userId, String puppetId) {
                return hosts;
            }
        };
    }
}
