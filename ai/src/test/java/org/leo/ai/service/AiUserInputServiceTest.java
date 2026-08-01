package org.leo.ai.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leo.ai.agent.AiToolContext;
import org.leo.ai.platform.PlatformAiState;
import org.leo.ai.platform.PlatformAiStateStore;
import org.leo.ai.thread.AiConversationStoreService;
import org.leo.core.entity.AiUserInputRequest;
import org.leo.core.entity.AiUserInputOption;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiUserInputServiceTest {

    private static final String STATE_ID = "platform-question-test";

    @AfterEach
    void cleanup() {
        AiToolContext.clear();
        PlatformAiStateStore.remove(STATE_ID);
    }

    @Test
    void persistsQuestionMarksRuntimeWaitingAndEmitsNode() {
        AiConversationStoreService store = mock(AiConversationStoreService.class);
        when(store.createUserInputRequest(any())).thenAnswer(invocation -> invocation.getArgument(0));
        PlatformAiState state = PlatformAiStateStore.create(STATE_ID);
        state.bindActiveTurnId("turn-1");
        state.bindActiveItemId("item-1");
        AiToolContext.setFromMemoryId(STATE_ID);

        AiUserInputService service = new AiUserInputService(store);
        Map<String, Object> result = service.request(
                "CLARIFICATION", "请选择目标范围", options("当前节点", "current_node", "SCOPE_CURRENT", "全部节点", "all_nodes", "SCOPE_ALL"),
                false, null, null, null, "LOW", 60L);

        assertEquals(PlatformAiState.STATUS_WAITING_FOR_USER, state.getRunStatus());
        assertTrue(state.isWaitingForUserInput());
        assertEquals(true, result.get("waitingForUser"));
        assertEquals("node", state.getAiSseEventQueue().peek().name());
        @SuppressWarnings("unchecked")
        Map<String, Object> request = (Map<String, Object>) result.get("request");
        assertEquals(List.of(Map.of(
                "label", "当前节点", "value", "current_node", "intent", "SCOPE_CURRENT"), Map.of(
                "label", "全部节点", "value", "all_nodes", "intent", "SCOPE_ALL")), request.get("options"));
        assertEquals(false, request.get("allowFreeText"));
        verify(store).createUserInputRequest(any(AiUserInputRequest.class));
    }

    @Test
    void confirmationBindsExactToolArgumentsHash() {
        AiConversationStoreService store = mock(AiConversationStoreService.class);
        when(store.createUserInputRequest(any())).thenAnswer(invocation -> invocation.getArgument(0));
        PlatformAiStateStore.create(STATE_ID);
        AiToolContext.setFromMemoryId(STATE_ID);
        AiUserInputService service = new AiUserInputService(store);

        @SuppressWarnings("unchecked")
        Map<String, Object> request = (Map<String, Object>) service.request(
                "CONFIRMATION", "确认删除用户吗？", options("确认删除", "confirm", "CONFIRM", "取消", "cancel", "REJECT"),
                false, "删除 user-1", "deleteUser", "{\"userId\":\"user-1\"}",
                "HIGH", 60L).get("request");

        assertEquals("deleteUser", request.get("toolName"));
        assertNotNull(request.get("argumentsHash"));
        assertEquals(64, String.valueOf(request.get("argumentsHash")).length());
    }

    @Test
    void confirmationRejectsUnboundAction() {
        PlatformAiStateStore.create(STATE_ID);
        AiToolContext.setFromMemoryId(STATE_ID);
        AiUserInputService service = new AiUserInputService(
                mock(AiConversationStoreService.class));

        assertThrows(RuntimeException.class, () -> service.request(
                "CONFIRMATION", "确认执行吗？", options("确认", "confirm", "CONFIRM", "取消", "cancel", "REJECT"),
                false, "危险动作", null, null, "HIGH", 60L));
    }

    @Test
    void confirmationHashIgnoresJsonWhitespaceAndObjectKeyOrder() {
        assertEquals(
                AiUserInputService.confirmationArgumentsHash(
                        "{\"userId\":\"user-1\",\"options\":{\"force\":true,\"depth\":2}}"),
                AiUserInputService.confirmationArgumentsHash(
                        "{ \"options\": { \"depth\": 2, \"force\": true }, \"userId\": \"user-1\" }"));
    }

    @Test
    void confirmationRequiresExplicitAcceptAndRejectOptions() {
        PlatformAiStateStore.create(STATE_ID);
        AiToolContext.setFromMemoryId(STATE_ID);
        AiUserInputService service = new AiUserInputService(
                mock(AiConversationStoreService.class));

        assertThrows(RuntimeException.class, () -> service.request(
                "CONFIRMATION", "确认执行吗？", options("稍后再说", "later", "DEFER"),
                true, "危险动作", "deleteUser", "{\"userId\":\"user-1\"}",
                "HIGH", 60L));
    }

    @Test
    void confirmationRejectsFreeText() {
        PlatformAiStateStore.create(STATE_ID);
        AiToolContext.setFromMemoryId(STATE_ID);
        AiUserInputService service = new AiUserInputService(mock(AiConversationStoreService.class));

        assertThrows(RuntimeException.class, () -> service.request(
                "CONFIRMATION", "确认执行吗？", options("确认", "confirm", "CONFIRM", "取消", "cancel", "REJECT"),
                true, "危险动作", "deleteUser", "{\"userId\":\"user-1\"}", "HIGH", 60L));
    }

    @Test
    void answeredQuestionIsInjectedIntoResumePrompt() {
        AiConversationStoreService store = mock(AiConversationStoreService.class);
        AiUserInputRequest request = new AiUserInputRequest();
        request.setRequestId("question-1");
        request.setThreadId("thread-1");
        request.setRequestType(AiUserInputRequest.TYPE_CLARIFICATION);
        request.setPrompt("使用哪个范围？");
        request.setStatus(AiUserInputRequest.STATUS_ANSWERED);
        request.setAnswer("只处理当前节点");
        when(store.findUserInputRequest("question-1")).thenReturn(request);

        String prompt = new AiUserInputService(store).resumePrompt(
                "thread-1", "question-1", "原始安全策略消息");

        assertTrue(prompt.contains("使用哪个范围？"));
        assertTrue(prompt.contains("只处理当前节点"));
        assertTrue(prompt.contains("原始安全策略消息"));
    }

    private static List<AiUserInputOption> options(String... values) {
        List<AiUserInputOption> result = new java.util.ArrayList<>();
        for (int i = 0; i < values.length; i += 3) {
            result.add(new AiUserInputOption(values[i], values[i + 1], values[i + 2]));
        }
        return result;
    }
}
