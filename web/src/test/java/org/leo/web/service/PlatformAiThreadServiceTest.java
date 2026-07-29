package org.leo.web.service;

import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leo.ai.channel.AiModelConfigService;
import org.leo.ai.platform.PlatformAiState;
import org.leo.ai.platform.PlatformAiStateStore;
import org.leo.ai.thread.AiConversationStoreService;
import org.leo.core.entity.AiThreadRecord;
import org.leo.core.entity.User;
import org.leo.web.exception.ApiException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformAiThreadServiceTest {

    private static final String THREAD_ID = "platform-ai-owned";

    @AfterEach
    void clearState() {
        PlatformAiStateStore.remove(THREAD_ID);
    }

    @Test
    void activatesOnlyThreadsOwnedByTheCurrentUser() {
        Fixture fixture = fixture("user-1");
        HttpSession session = mock(HttpSession.class);

        PlatformAiState state =
                fixture.service.activateThread(session, user("user-1"), THREAD_ID);

        assertEquals(7, state.getAiConfigId());
        assertEquals("plan", state.getMode());
        verify(session).setAttribute("platformAiStateId", THREAD_ID);
    }

    @Test
    void hidesForeignThreadsAndDoesNotMutateThem() {
        Fixture fixture = fixture("other-user");
        HttpSession session = mock(HttpSession.class);

        assertThrows(ApiException.class,
                () -> fixture.service.deleteThread(session, user("user-1"), THREAD_ID));

        verify(fixture.conversationStore, never()).deleteThread(THREAD_ID);
        verify(fixture.agentRegistry, never()).evict(THREAD_ID);
    }

    private Fixture fixture(String ownerId) {
        AiConversationStoreService conversationStore =
                mock(AiConversationStoreService.class);
        PlatformAiAgentRegistry agentRegistry = mock(PlatformAiAgentRegistry.class);
        AiThreadRecord record = new AiThreadRecord();
        record.setThreadId(THREAD_ID);
        record.setScope(AiConversationStoreService.SCOPE_PLATFORM);
        record.setUserId(ownerId);
        record.setConfigId(7);
        record.setMode("plan");
        when(conversationStore.findThread(THREAD_ID)).thenReturn(record);
        PlatformAiThreadService service = new PlatformAiThreadService(
                mock(AiModelConfigService.class), conversationStore,
                agentRegistry, mock(AiTurnProtocolService.class));
        return new Fixture(service, conversationStore, agentRegistry);
    }

    private User user(String id) {
        User user = new User();
        user.setUserId(id);
        return user;
    }

    private record Fixture(PlatformAiThreadService service,
                           AiConversationStoreService conversationStore,
                           PlatformAiAgentRegistry agentRegistry) {
    }
}
