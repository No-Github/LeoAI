package org.leo.web.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leo.ai.audit.AiAuditLogStore;
import org.leo.ai.platform.PlatformAiState;
import org.leo.ai.platform.PlatformAiStateStore;
import org.leo.ai.runtime.AiTurnOrchestrator;
import org.leo.ai.runtime.AiTurnOutcome;
import org.leo.ai.thread.AiConversationStoreService;
import org.leo.core.entity.AiChatAuditEntry;
import org.leo.core.entity.AiExecutionPolicy;
import org.leo.core.entity.AiThreadRecord;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiTurnApplicationServiceTest {

    @AfterEach
    void cleanState() {
        PlatformAiStateStore.remove("thread-1");
    }

    @Test
    void completesProtocolAndReleasesLeaseOnlyAfterRuntimeFutureCompletes() {
        Fixture fixture = fixture();

        CompletableFuture<Boolean> terminal = fixture.application.execute(fixture.turn);

        assertFalse(terminal.isDone());
        verify(fixture.protocol, never()).completeFromRuntime(
                anyString(), anyString(), nullable(String.class), anyString());
        verify(fixture.platformTurns, never()).releaseExecutionLease(fixture.state);

        fixture.runtime.complete(new AiTurnOrchestrator.TerminalResult(
                AiTurnOutcome.COMPLETED, null));

        assertTrue(terminal.join());
        verify(fixture.protocol).completeFromRuntime(
                "turn-1", "completed", null, "lease-1");
        verify(fixture.platformTurns).releaseExecutionLease(fixture.state);
    }

    @Test
    void propagatesRuntimeFailureStatusAndMessageToProtocolTerminal() {
        Fixture fixture = fixture();

        CompletableFuture<Boolean> terminal = fixture.application.execute(fixture.turn);
        fixture.runtime.complete(new AiTurnOrchestrator.TerminalResult(
                AiTurnOutcome.FAILED, "模型调用失败"));

        assertTrue(terminal.join());
        verify(fixture.protocol).completeFromRuntime(
                "turn-1", "failed", "模型调用失败", "lease-1");
        verify(fixture.platformTurns).releaseExecutionLease(fixture.state);
    }

    @Test
    void mapsRuntimeCancellationToInterruptedProtocolStatus() {
        Fixture fixture = fixture();

        CompletableFuture<Boolean> terminal = fixture.application.execute(fixture.turn);
        fixture.runtime.complete(new AiTurnOrchestrator.TerminalResult(
                AiTurnOutcome.CANCELLED, "用户手动停止"));

        assertTrue(terminal.join());
        verify(fixture.protocol).completeFromRuntime(
                "turn-1", "cancelled", "用户手动停止", "lease-1");
        verify(fixture.platformTurns).releaseExecutionLease(fixture.state);
    }

    private static Fixture fixture() {
        AiTurnProtocolService protocol = mock(AiTurnProtocolService.class);
        AiConversationStoreService store = mock(AiConversationStoreService.class);
        PlatformAiThreadService platformThreads = mock(PlatformAiThreadService.class);
        PlatformAiTurnService platformTurns = mock(PlatformAiTurnService.class);
        PuppetNodeAiThreadService puppetThreads = mock(PuppetNodeAiThreadService.class);
        PuppetNodeAiTurnService puppetTurns = mock(PuppetNodeAiTurnService.class);
        AiAuditLogStore auditLogStore = mock(AiAuditLogStore.class);
        AiTurnApplicationService application = new AiTurnApplicationService(
                protocol, store, platformThreads, platformTurns,
                puppetThreads, puppetTurns, auditLogStore);

        AiThreadRecord persisted = new AiThreadRecord();
        persisted.setThreadId("thread-1");
        persisted.setMode("auto");
        when(store.findThread("thread-1")).thenReturn(persisted);

        PlatformAiState state = PlatformAiStateStore.create("thread-1");
        when(platformTurns.tryClaimExecution(state)).thenAnswer(invocation -> {
            state.bindActiveLeaseToken("lease-1");
            return true;
        });
        AiChatAuditEntry audit = mock(AiChatAuditEntry.class);
        when(platformTurns.appendChatAudit(any(AiExecutionPolicy.class), eq("hello")))
                .thenReturn(audit);
        CompletableFuture<AiTurnOrchestrator.TerminalResult> runtime =
                new CompletableFuture<>();
        when(platformTurns.executeChat(
                eq(state), eq("session-1"), eq("hello"), eq("hello"),
                eq(audit), isNull(), anyLong(), nullable(String.class),
                any(), eq("turn-1"), eq("user-1"), eq("assistant-1")))
                .thenReturn(runtime);

        AiTurnCommandPayload command = AiTurnCommandPayload.create(
                AiTurnCommandPayload.SCOPE_PLATFORM,
                "session-1", "hello", "hello", null, null,
                List.of(), AiExecutionPolicy.defaultPolicy());
        AiTurnProtocolService.TurnSnapshot turn = new AiTurnProtocolService.TurnSnapshot(
                "turn-1", "thread-1", "running", "client-1",
                "user-1", "assistant-1", 1L, 2L, null,
                false, null, AiTurnCommandPayload.SCOPE_PLATFORM,
                command.toJson());
        when(protocol.completeFromRuntime(
                eq("turn-1"), anyString(), nullable(String.class), eq("lease-1")))
                .thenAnswer(invocation -> completedTurn(
                        invocation.getArgument(1), invocation.getArgument(2)));

        return new Fixture(
                application, protocol, platformTurns, state, runtime, turn);
    }

    private static AiTurnProtocolService.TurnSnapshot completedTurn(
            String runtimeStatus, String errorMessage) {
        String protocolStatus = switch (runtimeStatus) {
            case "completed" -> "completed";
            case "cancelled" -> "interrupted";
            default -> "failed";
        };
        return new AiTurnProtocolService.TurnSnapshot(
                "turn-1", "thread-1", protocolStatus, "client-1",
                "user-1", "assistant-1", 1L, 2L, 3L,
                false, errorMessage, AiTurnCommandPayload.SCOPE_PLATFORM, "{}");
    }

    private record Fixture(
            AiTurnApplicationService application,
            AiTurnProtocolService protocol,
            PlatformAiTurnService platformTurns,
            PlatformAiState state,
            CompletableFuture<AiTurnOrchestrator.TerminalResult> runtime,
            AiTurnProtocolService.TurnSnapshot turn) {
    }
}
