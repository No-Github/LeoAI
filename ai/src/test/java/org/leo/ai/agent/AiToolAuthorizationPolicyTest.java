package org.leo.ai.agent;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leo.ai.platform.PlatformAiState;
import org.leo.ai.platform.PlatformAiStateStore;
import org.leo.ai.service.AiUserInputService;
import org.leo.ai.service.AiOperationAssessmentService;
import org.leo.ai.thread.AiConversationStoreService;
import org.leo.core.entity.AiExecutionPolicy;
import org.leo.core.entity.AiUserInputRequest;
import org.leo.core.entity.User;
import org.leo.core.session.AiThread;
import org.leo.core.session.PuppetNodeSession;
import org.leo.core.session.PuppetNodeSessionContainer;
import org.leo.service.user.UserService;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;

class AiToolAuthorizationPolicyTest {

    private static final String PLATFORM_STATE_ID = "platform-auth-test";

    @AfterEach
    void cleanUp() {
        PlatformAiStateStore.remove(PLATFORM_STATE_ID);
        PuppetNodeSessionContainer.clearAllSessions();
        AiToolContext.clear();
    }

    @Test
    void hidesAdminToolsFromNormalUsers() {
        UserService users = mock(UserService.class);
        User normal = user("user-1", "normal");
        when(users.getUserById("user-1")).thenReturn(normal);
        PlatformAiState state = PlatformAiStateStore.create(PLATFORM_STATE_ID);
        state.setExecutionPolicy(AiExecutionPolicy.from(normal));
        AiToolAuthorizationPolicy policy = new AiToolAuthorizationPolicy(users);

        Map<String, AiServiceTool> tools = providedTools(policy.toolProvider(
                AiToolAuthorizationPolicy.AgentScope.PLATFORM,
                new SafeTools(), new AdminTools()), PLATFORM_STATE_ID);

        assertTrue(tools.containsKey("safeAction"));
        assertFalse(tools.containsKey("adminAction"));
    }

    @Test
    void rechecksPermissionImmediatelyBeforeExecution() {
        UserService users = mock(UserService.class);
        User admin = user("admin-1", "admin");
        User downgraded = user("admin-1", "normal");
        when(users.getUserById("admin-1")).thenReturn(admin, downgraded);
        PlatformAiState state = PlatformAiStateStore.create(PLATFORM_STATE_ID);
        state.setExecutionPolicy(AiExecutionPolicy.from(admin));
        AiToolAuthorizationPolicy policy = new AiToolAuthorizationPolicy(users);
        Map<String, AiServiceTool> tools = providedTools(policy.toolProvider(
                AiToolAuthorizationPolicy.AgentScope.PLATFORM,
                new AdminTools()), PLATFORM_STATE_ID);
        AiServiceTool adminTool = tools.get("adminAction");
        AiToolErrorHandler errors = new AiToolErrorHandler();

        ToolExecutionResult result = ToolService.executeWithErrorHandling(
                request("adminAction"), adminTool.toolExecutor(),
                context(PLATFORM_STATE_ID),
                errors::handleArguments, errors::handleExecution);

        assertTrue(result.isError());
        assertTrue(result.resultText().contains("TOOL_PERMISSION_DENIED"));
    }

    @Test
    void rejectsPuppetToolsWhenCallerDoesNotOwnSession() {
        UserService users = mock(UserService.class);
        User caller = user("user-1", "normal");
        when(users.getUserById("user-1")).thenReturn(caller);
        PuppetNodeSession session = new PuppetNodeSession();
        session.setSessionId("session-1");
        session.setCreateByUser("other-user");
        AiThread thread = session.createAiThread("thread-1", "test");
        thread.setExecutionPolicy(AiExecutionPolicy.from(caller));
        PuppetNodeSessionContainer.addSession("session-1", session);
        AiToolAuthorizationPolicy policy = new AiToolAuthorizationPolicy(users);

        Map<String, AiServiceTool> tools = providedTools(policy.toolProvider(
                AiToolAuthorizationPolicy.AgentScope.PUPPET_NODE,
                new SafeTools()), "session-1:thread-1");

        assertTrue(tools.isEmpty());
    }

    @Test
    void destructiveToolRequiresAndConsumesExactConfirmation() {
        UserService users = mock(UserService.class);
        User normal = user("user-1", "normal");
        when(users.getUserById("user-1")).thenReturn(normal);
        PlatformAiState state = PlatformAiStateStore.create(PLATFORM_STATE_ID);
        state.setExecutionPolicy(AiExecutionPolicy.from(normal));
        state.bindActiveConfirmationRequestId("question-1");
        AiConversationStoreService store = mock(AiConversationStoreService.class);
        AiUserInputRequest confirmation = confirmation("deleteRecord", "{\"id\":1}");
        when(store.findUserInputRequest("question-1")).thenReturn(confirmation);
        when(store.consumeConfirmation(
                eq("question-1"), eq(PLATFORM_STATE_ID), eq("deleteRecord"),
                eq(confirmation.getArgumentsHash()), anyLong()))
                .thenReturn(true, false);
        AiToolCatalog catalog = new AiToolCatalog();
        AiOperationAssessmentService assessments = new AiOperationAssessmentService(catalog);
        register(catalog, new DestructiveTools());
        AiToolContext.setFromMemoryId(PLATFORM_STATE_ID);
        AiToolContext.setExecutionPolicy(AiExecutionPolicy.from(normal));
        assessments.assess(PLATFORM_STATE_ID, "deleteRecord", "{\"id\":1}",
                "HIGH", true, "删除数据", "数据不可恢复", null);
        AiToolAuthorizationPolicy policy = new AiToolAuthorizationPolicy(
                users, new AiToolExecutionBoundary(), null, store,
                catalog, new AgentRuntimeResolver(), null, assessments);
        AiServiceTool tool = providedTools(policy.toolProvider(
                AiToolAuthorizationPolicy.AgentScope.PLATFORM,
                new DestructiveTools()), PLATFORM_STATE_ID).get("deleteRecord");
        AiToolErrorHandler errors = new AiToolErrorHandler();

        ToolExecutionResult first = ToolService.executeWithErrorHandling(
                request("deleteRecord", "{\"id\":1}"), tool.toolExecutor(),
                context(PLATFORM_STATE_ID), errors::handleArguments, errors::handleExecution);
        ToolExecutionResult second = ToolService.executeWithErrorHandling(
                request("deleteRecord", "{\"id\":1}"), tool.toolExecutor(),
                context(PLATFORM_STATE_ID), errors::handleArguments, errors::handleExecution);

        assertFalse(first.isError());
        assertTrue(second.isError());
        assertTrue(second.resultText().contains("OPERATION_ASSESSMENT_REQUIRED"));
    }

    @Test
    void destructiveToolRejectsChangedArguments() {
        UserService users = mock(UserService.class);
        User normal = user("user-1", "normal");
        when(users.getUserById("user-1")).thenReturn(normal);
        PlatformAiState state = PlatformAiStateStore.create(PLATFORM_STATE_ID);
        state.setExecutionPolicy(AiExecutionPolicy.from(normal));
        state.bindActiveConfirmationRequestId("question-1");
        AiConversationStoreService store = mock(AiConversationStoreService.class);
        when(store.findUserInputRequest("question-1"))
                .thenReturn(confirmation("deleteRecord", "{\"id\":1}"));
        AiToolCatalog catalog = new AiToolCatalog();
        AiOperationAssessmentService assessments = new AiOperationAssessmentService(catalog);
        register(catalog, new DestructiveTools());
        AiToolContext.setFromMemoryId(PLATFORM_STATE_ID);
        AiToolContext.setExecutionPolicy(AiExecutionPolicy.from(normal));
        assessments.assess(PLATFORM_STATE_ID, "deleteRecord", "{\"id\":1}",
                "HIGH", true, "删除数据", "数据不可恢复", null);
        AiToolAuthorizationPolicy policy = new AiToolAuthorizationPolicy(
                users, new AiToolExecutionBoundary(), null, store,
                catalog, new AgentRuntimeResolver(), null, assessments);
        AiServiceTool tool = providedTools(policy.toolProvider(
                AiToolAuthorizationPolicy.AgentScope.PLATFORM,
                new DestructiveTools()), PLATFORM_STATE_ID).get("deleteRecord");

        ToolExecutionResult result = ToolService.executeWithErrorHandling(
                request("deleteRecord", "{\"id\":2}"), tool.toolExecutor(),
                context(PLATFORM_STATE_ID), new AiToolErrorHandler()::handleArguments,
                new AiToolErrorHandler()::handleExecution);

        assertTrue(result.isError());
        assertTrue(result.resultText().contains("OPERATION_ASSESSMENT_REQUIRED"));
    }

    @Test
    void businessMutationRequiresMatchingAgentAssessment() {
        UserService users = mock(UserService.class);
        User normal = user("user-1", "normal");
        when(users.getUserById("user-1")).thenReturn(normal);
        PlatformAiState state = PlatformAiStateStore.create(PLATFORM_STATE_ID);
        state.setExecutionPolicy(AiExecutionPolicy.from(normal));
        AiToolCatalog catalog = new AiToolCatalog();
        AiOperationAssessmentService assessments = new AiOperationAssessmentService(catalog);
        AiToolAuthorizationPolicy policy = new AiToolAuthorizationPolicy(
                users, new AiToolExecutionBoundary(), null, null,
                catalog, new AgentRuntimeResolver(), null, assessments);
        AiServiceTool tool = providedTools(policy.toolProvider(
                AiToolAuthorizationPolicy.AgentScope.PLATFORM,
                new MutationTools()), PLATFORM_STATE_ID).get("mutate");
        AiToolErrorHandler errors = new AiToolErrorHandler();

        ToolExecutionResult rejected = ToolService.executeWithErrorHandling(
                request("mutate", "{\"value\":\"one\"}"), tool.toolExecutor(),
                context(PLATFORM_STATE_ID), errors::handleArguments, errors::handleExecution);
        assertTrue(rejected.isError());
        assertTrue(rejected.resultText().contains("OPERATION_ASSESSMENT_REQUIRED"));

        AiToolContext.setFromMemoryId(PLATFORM_STATE_ID);
        AiToolContext.setExecutionPolicy(AiExecutionPolicy.from(normal));
        assessments.assess(PLATFORM_STATE_ID, "mutate", "{\"value\":\"one\"}",
                "LOW", false, "只修改当前任务的可逆状态", null, null);
        ToolExecutionResult accepted = ToolService.executeWithErrorHandling(
                request("mutate", "{\"value\":\"one\"}"), tool.toolExecutor(),
                context(PLATFORM_STATE_ID), errors::handleArguments, errors::handleExecution);
        assertFalse(accepted.isError());
        assertTrue(accepted.resultText().contains("one"));
    }

    private static Map<String, AiServiceTool> providedTools(
            ToolProvider provider, String memoryId) {
        ToolProviderRequest request = ToolProviderRequest.builder()
                .invocationContext(context(memoryId))
                .userMessage(UserMessage.from("test"))
                .build();
        return provider.provideTools(request).aiServiceTools().stream()
                .collect(Collectors.toMap(AiServiceTool::name, Function.identity()));
    }

    private static void register(AiToolCatalog catalog, Object source) {
        ToolService.findTools(source).forEach(tool -> catalog.register(source, tool));
    }

    private static InvocationContext context(String memoryId) {
        return InvocationContext.builder().chatMemoryId(memoryId).build();
    }

    private static ToolExecutionRequest request(String name) {
        return request(name, "{}");
    }

    private static ToolExecutionRequest request(String name, String arguments) {
        return ToolExecutionRequest.builder()
                .id("call-" + name)
                .name(name)
                .arguments(arguments)
                .build();
    }

    private static AiUserInputRequest confirmation(String toolName, String arguments) {
        AiUserInputRequest request = new AiUserInputRequest();
        request.setRequestId("question-1");
        request.setThreadId(PLATFORM_STATE_ID);
        request.setRequestType(AiUserInputRequest.TYPE_CONFIRMATION);
        request.setStatus(AiUserInputRequest.STATUS_ANSWERED);
        request.setAnswer("确认执行");
        request.setToolName(toolName);
        request.setArgumentsHash(AiUserInputService.confirmationArgumentsHash(arguments));
        return request;
    }

    private static User user(String userId, String privilege) {
        User user = new User();
        user.setUserId(userId);
        user.setUserName(userId);
        user.setPrivilege(privilege);
        user.setStatus(1);
        return user;
    }

    private static class SafeTools {
        @Tool
        public String safeAction() {
            return "ok";
        }
    }

    private static class DestructiveTools {
        @Tool
        @AiToolPolicy(kind = AiToolKind.COMMAND,
                operation = AiToolOperation.DESTRUCTIVE)
        public String deleteRecord(int id) {
            return "deleted-" + id;
        }
    }

    private static class MutationTools {
        @Tool
        @AiToolPolicy(kind = AiToolKind.COMMAND,
                operation = AiToolOperation.WRITE)
        public String mutate(String value) {
            return value;
        }
    }

    @AiToolAccess(AiToolAccess.Level.ADMIN)
    private static class AdminTools {
        @Tool
        public String adminAction() {
            return "admin";
        }
    }
}
