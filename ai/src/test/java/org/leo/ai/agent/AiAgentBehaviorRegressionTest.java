package org.leo.ai.agent;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import org.junit.jupiter.api.Test;
import org.leo.ai.testing.ScriptedChatModel;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.leo.ai.testing.ScriptedChatModel.response;

/** Agent 工具循环的确定性行为回归场景。 */
class AiAgentBehaviorRegressionTest {

    @Test
    void modelCanCorrectInvalidArgumentsFromStructuredToolError() {
        ScriptedChatModel model = new ScriptedChatModel()
                .thenToolCall("call-invalid", "toggleCache", "{\"cache\":\"true\"}")
                .then(request -> {
                    String error = lastToolResult(request.messages());
                    assertTrue(error.contains("\"protocol\":\"leo.tool.error.v1\""));
                    assertTrue(error.contains("\"code\":\"INVALID_TOOL_ARGUMENTS\""));
                    return response(dev.langchain4j.data.message.AiMessage.from(
                            dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                                    .id("call-corrected")
                                    .name("toggleCache")
                                    .arguments("{\"cache\":true}")
                                    .build()));
                })
                .then(request -> {
                    assertEquals("cache=true", lastToolResult(request.messages()));
                    return response(dev.langchain4j.data.message.AiMessage.from("参数已修正并执行成功"));
                });
        RegressionTools tools = new RegressionTools();
        AiToolErrorHandler errors = new AiToolErrorHandler();
        RegressionAgent agent = agent(model, tools, errors);

        String answer;
        try (AiToolErrorHandler.TurnScope ignored = errors.beginTurn("memory-1")) {
            answer = agent.chat("memory-1", "启用缓存");
        }

        assertEquals("参数已修正并执行成功", answer);
        assertEquals(1, tools.successfulCalls.get());
        assertEquals(3, model.requests().size());
        model.assertExhausted();
    }

    @Test
    void permissionFailureIsReturnedAsUserRecoverableAndNotRetried() {
        ScriptedChatModel model = new ScriptedChatModel()
                .thenToolCall("call-denied", "adminAction", "{}")
                .then(request -> {
                    String error = lastToolResult(request.messages());
                    assertTrue(error.contains("\"code\":\"TOOL_PERMISSION_DENIED\""));
                    assertTrue(error.contains("\"recoverableBy\":\"USER\""));
                    assertTrue(error.contains("\"retryable\":false"));
                    return response(dev.langchain4j.data.message.AiMessage.from(
                            "当前权限不足，需要管理员授权"));
                });
        RegressionTools tools = new RegressionTools();
        AiToolErrorHandler errors = new AiToolErrorHandler();
        RegressionAgent agent = agent(model, tools, errors);

        String answer;
        try (AiToolErrorHandler.TurnScope ignored = errors.beginTurn("memory-2")) {
            answer = agent.chat("memory-2", "执行管理操作");
        }

        assertEquals("当前权限不足，需要管理员授权", answer);
        assertEquals(1, tools.deniedCalls.get());
        assertEquals(2, model.requests().size());
        model.assertExhausted();
    }

    @Test
    void hallucinatedToolNameCanBeCorrectedWithinTheSameTurn() {
        ScriptedChatModel model = new ScriptedChatModel()
                .thenToolCall("call-unknown", "imaginedTool", "{}")
                .then(request -> {
                    String error = lastToolResult(request.messages());
                    assertTrue(error.contains("\"code\":\"UNKNOWN_TOOL\""));
                    return response(dev.langchain4j.data.message.AiMessage.from(
                            dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                                    .id("call-known")
                                    .name("toggleCache")
                                    .arguments("{\"cache\":false}")
                                    .build()));
                })
                .then(request -> {
                    assertEquals("cache=false", lastToolResult(request.messages()));
                    return response(dev.langchain4j.data.message.AiMessage.from("已改用有效工具"));
                });
        RegressionTools tools = new RegressionTools();
        AiToolErrorHandler errors = new AiToolErrorHandler();
        RegressionAgent agent = agent(model, tools, errors);

        String answer = agent.chat("memory-3", "使用一个有效工具");

        assertEquals("已改用有效工具", answer);
        assertEquals(1, tools.successfulCalls.get());
        assertEquals(3, model.requests().size());
        model.assertExhausted();
    }

    @Test
    void subsequentTurnReceivesCommittedConversationMemory() {
        ScriptedChatModel model = new ScriptedChatModel()
                .then(request -> {
                    assertTrue(request.messages().stream()
                            .anyMatch(message -> message instanceof dev.langchain4j.data.message.UserMessage user
                                    && "第一问".equals(user.singleText())));
                    return response(dev.langchain4j.data.message.AiMessage.from("第一答"));
                })
                .then(request -> {
                    assertTrue(request.messages().stream()
                            .anyMatch(message -> message instanceof dev.langchain4j.data.message.AiMessage ai
                                    && "第一答".equals(ai.text())));
                    assertTrue(request.messages().stream()
                            .anyMatch(message -> message instanceof dev.langchain4j.data.message.UserMessage user
                                    && "第二问".equals(user.singleText())));
                    return response(dev.langchain4j.data.message.AiMessage.from("第二答"));
                });
        AiToolErrorHandler errors = new AiToolErrorHandler();
        RegressionAgent agent = agent(model, new RegressionTools(), errors);

        assertEquals("第一答", agent.chat("memory-4", "第一问"));
        assertEquals("第二答", agent.chat("memory-4", "第二问"));
        assertEquals(2, model.requests().size());
        model.assertExhausted();
    }

    private static RegressionAgent agent(ScriptedChatModel model,
                                         RegressionTools tools,
                                         AiToolErrorHandler errors) {
        return AiServices.builder(RegressionAgent.class)
                .chatModel(model)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(30)
                        .build())
                .tools(tools)
                .toolArgumentsErrorHandler(errors::handleArguments)
                .toolExecutionErrorHandler(errors::handleExecution)
                .hallucinatedToolNameStrategy(errors::handleUnknownTool)
                .build();
    }

    private static String lastToolResult(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof ToolExecutionResultMessage result) {
                return result.text();
            }
        }
        throw new AssertionError("模型请求中没有工具结果");
    }

    private interface RegressionAgent {
        String chat(@MemoryId String memoryId, @UserMessage String message);
    }

    private static final class RegressionTools {
        private final AtomicInteger successfulCalls = new AtomicInteger();
        private final AtomicInteger deniedCalls = new AtomicInteger();

        @Tool(name = "toggleCache")
        public String toggleCache(@P(name = "cache") boolean cache) {
            successfulCalls.incrementAndGet();
            return "cache=" + cache;
        }

        @Tool(name = "adminAction")
        public String adminAction() {
            deniedCalls.incrementAndGet();
            throw new SecurityException("Authorization=Bearer private-token");
        }
    }
}
