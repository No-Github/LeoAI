package org.leo.web.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leo.core.session.PuppetNodeSession;
import org.leo.core.session.PuppetNodeSessionContainer;
import org.leo.service.PuppetService;
import org.leo.service.puppetnode.PuppetNodeFactory;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PuppetNodeLifecycleServiceTest {

    @AfterEach
    void clearSessions() {
        PuppetNodeSessionContainer.clearAllSessions();
    }

    @Test
    void createsInitialThreadThroughSameServiceAsManualCreation() {
        PuppetNodeAiThreadService aiThreadService = mock(PuppetNodeAiThreadService.class);
        PuppetNodeLifecycleService lifecycleService = new PuppetNodeLifecycleService(
                mock(PuppetService.class), mock(PuppetNodeFactory.class), aiThreadService);
        PuppetNodeSession session = new PuppetNodeSession();
        session.setSessionId("session-1");
        session.setCreateByUser("user-1");
        session.setPuppetId("puppet-1");

        when(aiThreadService.createThread(same(session), eq("对话 1"), isNull()))
                .thenAnswer(invocation -> {
                    assertSame(session, PuppetNodeSessionContainer.getSession("session-1"));
                    return null;
                });

        lifecycleService.registerSessionWithInitialAiThread(session, "puppet-1");

        verify(aiThreadService).createThread(same(session), eq("对话 1"), isNull());
    }
}
