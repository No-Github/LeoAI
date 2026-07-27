package org.leo.web.service;

import org.junit.jupiter.api.Test;
import org.leo.ai.thread.AiConversationStoreService;
import org.leo.core.entity.AiTurnRecord;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiTurnProtocolServiceTest {

    @Test
    void reusesIdempotencyKeyOnlyForTheSameCommand() {
        AiConversationStoreService store = mock(AiConversationStoreService.class);
        AiTurnRecord existing = existingTurn();
        when(store.findProtocolTurnByClientId("thread-1", "client-1"))
                .thenReturn(existing);
        AiTurnProtocolService service = new AiTurnProtocolService(store);

        AiTurnProtocolService.Reservation reservation = service.begin(
                "thread-1", "client-1", "platform", "{\"message\":\"hello\"}");

        assertTrue(reservation.reused());
    }

    @Test
    void rejectsIdempotencyKeyReusedForDifferentCommand() {
        AiConversationStoreService store = mock(AiConversationStoreService.class);
        AiTurnRecord existing = existingTurn();
        when(store.findProtocolTurnByClientId("thread-1", "client-1"))
                .thenReturn(existing);
        AiTurnProtocolService service = new AiTurnProtocolService(store);

        assertThrows(IllegalStateException.class, () -> service.begin(
                "thread-1", "client-1", "platform", "{\"message\":\"different\"}"));
        verify(store, never()).reserveProtocolTurn(any());
    }

    private AiTurnRecord existingTurn() {
        AiTurnRecord row = new AiTurnRecord();
        row.setTurnId("turn-1");
        row.setThreadId("thread-1");
        row.setProtocolStatus("inProgress");
        row.setDispatchStatus("queued");
        row.setCommandScope("platform");
        row.setCommandJson("{\"message\":\"hello\"}");
        row.setClientUserMessageId("client-1");
        row.setUserItemId("user-1");
        row.setAssistantItemId("assistant-1");
        row.setCreatedAt(100L);
        return row;
    }
}
