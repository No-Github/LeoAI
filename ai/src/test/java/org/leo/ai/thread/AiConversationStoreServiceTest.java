package org.leo.ai.thread;

import org.junit.jupiter.api.Test;
import org.leo.ai.runtime.AiTurnTrace;
import org.leo.core.entity.AiMessageRecord;
import org.leo.core.entity.AiRunRecord;
import org.leo.core.entity.AiTurnRecord;
import org.leo.core.session.AiThread;
import org.leo.dao.mapper.AiConversationMapper;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiConversationStoreServiceTest {

    @Test
    void beginsTurnWithLinkedRunAndPendingUserMessage() {
        AiConversationMapper mapper = mock(AiConversationMapper.class);
        AiConversationStoreService service = new AiConversationStoreService(mapper);

        AiConversationStoreService.PersistedTurn turn = service.beginTurn(
                "thread-1", 7, "guarded input", "visible input",
                Map.of("name", "a.txt"), 100L, "{\"model\":\"test\"}",
                AiTurnTrace.start("test", "thread-1", 100L));

        ArgumentCaptor<AiRunRecord> runCaptor = ArgumentCaptor.forClass(AiRunRecord.class);
        ArgumentCaptor<AiMessageRecord> messageCaptor = ArgumentCaptor.forClass(AiMessageRecord.class);
        InOrder order = inOrder(mapper);
        order.verify(mapper).insertTurn(any(AiTurnRecord.class));
        order.verify(mapper).insertRun(runCaptor.capture());
        order.verify(mapper).insertMessage(messageCaptor.capture());

        AiRunRecord run = runCaptor.getValue();
        AiMessageRecord message = messageCaptor.getValue();
        assertEquals(turn.runId(), run.getRunId());
        assertEquals(turn.turnId(), run.getTurnId());
        assertNotNull(run.getTraceId());
        assertNotNull(run.getTraceJson());
        assertEquals(turn.runId(), message.getRunId());
        assertEquals(turn.turnId(), message.getTurnId());
        assertEquals(AiConversationStoreService.MESSAGE_PENDING, message.getStatus());
        assertEquals("visible input", message.getContent());
        assertNotNull(turn.userMessageId());
    }

    @Test
    void completesTurnByCommittingBothMessagesAndFinishingRun() {
        AiConversationMapper mapper = mock(AiConversationMapper.class);
        AiConversationStoreService service = new AiConversationStoreService(mapper);
        var turn = new AiConversationStoreService.PersistedTurn(
                "turn-1", "run-1", "thread-1", "message-1", 100L);

        service.completeTurn(turn, "answer", List.of(Map.of("kind", "text")),
                Map.of("ok", true), null, 2);

        ArgumentCaptor<AiMessageRecord> messageCaptor = ArgumentCaptor.forClass(AiMessageRecord.class);
        verify(mapper).insertMessage(messageCaptor.capture());
        AiMessageRecord assistant = messageCaptor.getValue();
        assertEquals("assistant", assistant.getRole());
        assertEquals("turn-1", assistant.getTurnId());
        assertEquals("run-1", assistant.getRunId());
        assertEquals(AiConversationStoreService.MESSAGE_COMMITTED, assistant.getStatus());
        verify(mapper).updateTurnMessageStatus(
                "thread-1", "turn-1", AiConversationStoreService.MESSAGE_COMMITTED);
        ArgumentCaptor<AiTurnRecord> turnCaptor = ArgumentCaptor.forClass(AiTurnRecord.class);
        verify(mapper).finishTurn(turnCaptor.capture());
        assertEquals(AiConversationStoreService.MESSAGE_COMMITTED, turnCaptor.getValue().getStatus());

        ArgumentCaptor<AiRunRecord> runCaptor = ArgumentCaptor.forClass(AiRunRecord.class);
        verify(mapper).finishRun(runCaptor.capture());
        assertEquals(AiThread.STATUS_COMPLETED, runCaptor.getValue().getStatus());
        assertEquals("answer", runCaptor.getValue().getOutput());
        assertNull(runCaptor.getValue().getErrorMessage());
    }

    @Test
    void discardsFailedTurnWithoutReturningItAsCommittedHistory() {
        AiConversationMapper mapper = mock(AiConversationMapper.class);
        AiConversationStoreService service = new AiConversationStoreService(mapper);
        var turn = new AiConversationStoreService.PersistedTurn(
                "turn-1", "run-1", "thread-1", "message-1", 100L);

        service.discardTurn(turn, AiThread.STATUS_CANCELLED, "cancelled",
                "用户取消", "用户取消", 0);

        verify(mapper).updateTurnMessageStatus(
                "thread-1", "turn-1", AiConversationStoreService.MESSAGE_DISCARDED);
        ArgumentCaptor<AiTurnRecord> turnCaptor = ArgumentCaptor.forClass(AiTurnRecord.class);
        verify(mapper).finishTurn(turnCaptor.capture());
        assertEquals(AiConversationStoreService.MESSAGE_DISCARDED, turnCaptor.getValue().getStatus());
        ArgumentCaptor<AiRunRecord> runCaptor = ArgumentCaptor.forClass(AiRunRecord.class);
        verify(mapper).finishRun(runCaptor.capture());
        assertEquals(AiThread.STATUS_CANCELLED, runCaptor.getValue().getStatus());
        assertEquals("cancelled", runCaptor.getValue().getErrorCategory());
        assertEquals("用户取消", runCaptor.getValue().getErrorMessage());
        assertEquals("用户取消", runCaptor.getValue().getRawErrorMessage());
    }

    @Test
    void exposesTurnMetadataWithoutChangingLegacyMessageShape() {
        AiConversationMapper mapper = mock(AiConversationMapper.class);
        AiConversationStoreService service = new AiConversationStoreService(mapper);
        AiMessageRecord row = new AiMessageRecord();
        row.setMessageId("message-1");
        row.setThreadId("thread-1");
        row.setTurnId("turn-1");
        row.setRunId("run-1");
        row.setMessageSeq(3L);
        row.setStatus(AiConversationStoreService.MESSAGE_COMMITTED);
        row.setRole("user");
        row.setContent("hello");
        row.setTimestamp(100L);
        when(mapper.listMessages("thread-1", 0, 20)).thenReturn(List.of(row));

        Map<String, Object> message = service.listMessages("thread-1", 0, 20).get(0);

        assertEquals("message-1", message.get("messageId"));
        assertEquals("hello", message.get("content"));
        assertEquals("turn-1", message.get("turnId"));
        assertEquals("run-1", message.get("runId"));
        assertEquals(3L, message.get("sequence"));
        assertEquals(AiConversationStoreService.MESSAGE_COMMITTED, message.get("status"));
    }
}
