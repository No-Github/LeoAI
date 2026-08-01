package org.leo.ai.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderResult;
import dev.langchain4j.service.tool.ToolService;
import org.leo.ai.platform.PlatformAiState;
import org.leo.ai.platform.PlatformAiStateStore;
import org.leo.ai.service.AiUserInputService;
import org.leo.ai.thread.AiConversationStoreService;
import org.leo.core.entity.AiExecutionPolicy;
import org.leo.core.entity.AiUserInputRequest;
import org.leo.core.entity.User;
import org.leo.core.security.AccessPolicy;
import org.leo.core.session.AiThread;
import org.leo.core.session.PuppetNodeSession;
import org.leo.core.session.PuppetNodeSessionContainer;
import org.leo.service.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Agent 工具的统一授权边界。
 *
 * <p>工具列表会按当前身份过滤，真正执行时还会再次校验，避免提示词绕过、
 * 旧工具请求复用或运行期间角色变更造成越权。
 */
@Component
public class AiToolAuthorizationPolicy {

    public enum AgentScope {
        PUPPET_NODE,
        PLATFORM
    }

    private static final Logger log =
            LoggerFactory.getLogger(AiToolAuthorizationPolicy.class);

    private final UserService userService;
    private final AiToolExecutionBoundary executionBoundary;
    private final AiToolResultArchiveTools archiveTools;
    private final AiConversationStoreService conversationStore;

    public AiToolAuthorizationPolicy(UserService userService) {
        this(userService, new AiToolExecutionBoundary(), null, null);
    }

    public AiToolAuthorizationPolicy(UserService userService,
                                     AiToolExecutionBoundary executionBoundary,
                                     AiToolResultArchiveTools archiveTools) {
        this(userService, executionBoundary, archiveTools, null);
    }

    @Autowired
    public AiToolAuthorizationPolicy(UserService userService,
                                     AiToolExecutionBoundary executionBoundary,
                                     AiToolResultArchiveTools archiveTools,
                                     AiConversationStoreService conversationStore) {
        this.userService = userService;
        this.executionBoundary = executionBoundary;
        this.conversationStore = conversationStore;
        this.archiveTools = archiveTools != null
                ? archiveTools
                : new AiToolResultArchiveTools(executionBoundary.archive());
    }

    public ToolProvider toolProvider(AgentScope scope, Object... toolObjects) {
        List<SecuredTool> securedTools = secureTools(scope, toolObjects);
        return request -> {
            AiExecutionPolicy policy = resolvePolicy(scope, request.chatMemoryId());
            List<AiServiceTool> visible = securedTools.stream()
                    .filter(tool -> isAllowed(tool.access(), policy))
                    .map(SecuredTool::tool)
                    .toList();
            return new ToolProviderResult(visible);
        };
    }

    public void bindContext(AgentScope scope, Object memoryId) {
        AiToolContext.setFromMemoryId(memoryId);
        AiToolContext.setExecutionPolicy(resolvePolicy(scope, memoryId));
        AiToolContext.setConfirmationRequestId(resolveConfirmationRequestId(scope, memoryId));
    }

    private List<SecuredTool> secureTools(AgentScope scope, Object... toolObjects) {
        List<SecuredTool> secured = new ArrayList<>();
        if (toolObjects == null) return secured;
        for (Object source : Arrays.asList(toolObjects)) {
            if (source == null) continue;
            AiToolAccess classAccess = source.getClass().getAnnotation(AiToolAccess.class);
            AiToolAccess.Level access = classAccess != null
                    ? classAccess.value() : AiToolAccess.Level.AUTHENTICATED;
            for (AiServiceTool tool : ToolService.findTools(source)) {
                secured.add(new SecuredTool(
                        wrap(scope, tool, access), access));
            }
        }
        AiToolAccess.Level archiveAccess = AiToolAccess.Level.AUTHENTICATED;
        for (AiServiceTool tool : ToolService.findTools(archiveTools)) {
            secured.add(new SecuredTool(
                    wrap(scope, tool, archiveAccess), archiveAccess));
        }
        return List.copyOf(secured);
    }

    private AiServiceTool wrap(AgentScope scope,
                               AiServiceTool tool,
                               AiToolAccess.Level access) {
        ToolExecutor delegate = tool.toolExecutor();
        ToolExecutor securedExecutor = new ToolExecutor() {
            @Override
            public String execute(ToolExecutionRequest request, Object memoryId) {
                InvocationContext context = InvocationContext.builder()
                        .chatMemoryId(memoryId)
                        .build();
                return executeWithContext(request, context).resultText();
            }

            @Override
            public ToolExecutionResult executeWithContext(
                    ToolExecutionRequest request,
                    InvocationContext context) {
                Object memoryId = context != null ? context.chatMemoryId() : null;
                bindContext(scope, memoryId);
                AiExecutionPolicy policy = AiToolContext.getExecutionPolicy();
                if (!isAllowed(access, policy)) {
                    log.warn("拒绝 Agent 工具调用 scope={} tool={} userId={} privilege={}",
                            scope,
                            request != null ? request.name() : "unknown",
                            policy.getUserId(), policy.getPrivilege());
                    throw new SecurityException("当前身份无权执行该工具");
                }
                if (!"request_user_input".equals(tool.name())
                        && isWaitingForUserInput(scope, memoryId)) {
                    throw AiToolException.userActionRequired(
                            "USER_INPUT_PENDING",
                            "当前任务正在等待用户回答，不能继续执行其他工具。",
                        "停止工具调用并等待用户回答；不要自行假设用户意图。");
                }
                authorizeOperation(memoryId, tool.name(), request);
                return executionBoundary.execute(
                        scope, tool.name(), delegate, request, context);
            }
        };
        return tool.toBuilder().toolExecutor(securedExecutor).build();
    }

    private void authorizeOperation(Object memoryId, String toolName,
                                    ToolExecutionRequest request) {
        if (AiToolOperation.classify(toolName) != AiToolOperation.DESTRUCTIVE) return;
        String confirmationId = AiToolContext.getConfirmationRequestId();
        String threadId = AiToolContext.getThreadId();
        if (threadId == null || threadId.isBlank()) threadId = String.valueOf(memoryId);
        if (conversationStore == null || confirmationId == null || confirmationId.isBlank()) {
            throw confirmationRequired(toolName);
        }
        AiUserInputRequest confirmation = conversationStore.findUserInputRequest(confirmationId);
        String argumentsHash = AiUserInputService.confirmationArgumentsHash(
                request != null && request.arguments() != null ? request.arguments() : "{}");
        if (confirmation == null
                || !threadId.equals(confirmation.getThreadId())
                || !AiUserInputRequest.TYPE_CONFIRMATION.equals(confirmation.getRequestType())
                || !AiUserInputRequest.STATUS_ANSWERED.equals(confirmation.getStatus())
                || !AiUserInputService.isAffirmativeAnswer(confirmation.getAnswer())
                || !toolName.equals(confirmation.getToolName())
                || !java.util.Objects.equals(argumentsHash, confirmation.getArgumentsHash())
                || !conversationStore.consumeConfirmation(
                        confirmationId, threadId, toolName, argumentsHash,
                        System.currentTimeMillis())) {
            throw confirmationRequired(toolName);
        }
    }

    private AiToolException confirmationRequired(String toolName) {
        return AiToolException.userActionRequired(
                "USER_CONFIRMATION_REQUIRED",
                "执行高风险工具 " + toolName + " 前需要用户明确确认。",
                "调用 request_user_input(type=CONFIRMATION) 绑定准确工具名和完整参数，"
                        + "等待用户选择确认后再执行；不要自行假设用户同意。");
    }

    private String resolveConfirmationRequestId(AgentScope scope, Object memoryId) {
        String value = memoryId != null ? String.valueOf(memoryId) : null;
        if (value == null || value.isBlank()) return null;
        if (scope == AgentScope.PLATFORM) {
            PlatformAiState state = PlatformAiStateStore.get(value);
            return state != null ? state.getActiveConfirmationRequestId() : null;
        }
        PuppetNodeSession session = resolvePuppetSession(value);
        if (session == null) return null;
        int separator = value.indexOf(':');
        String threadId = separator > 0 && separator < value.length() - 1
                ? value.substring(separator + 1) : null;
        AiThread thread = threadId != null ? session.getAiThread(threadId) : session.getActiveThread();
        return thread != null ? thread.getActiveConfirmationRequestId() : null;
    }

    AiExecutionPolicy resolvePolicy(AgentScope scope, Object memoryId) {
        AiExecutionPolicy runtimePolicy = runtimePolicy(scope, memoryId);
        if (runtimePolicy == null || runtimePolicy.getUserId() == null
                || runtimePolicy.getUserId().isBlank()) {
            return AiExecutionPolicy.defaultPolicy();
        }
        User user = userService.getUserById(runtimePolicy.getUserId());
        if (user == null || !Integer.valueOf(1).equals(user.getStatus())) {
            return AiExecutionPolicy.defaultPolicy();
        }
        if (scope == AgentScope.PUPPET_NODE) {
            PuppetNodeSession session = resolvePuppetSession(memoryId);
            if (!AccessPolicy.canAccessSession(session, user)) {
                return AiExecutionPolicy.defaultPolicy();
            }
        }
        return AiExecutionPolicy.from(user);
    }

    private AiExecutionPolicy runtimePolicy(AgentScope scope, Object memoryId) {
        String value = memoryId != null ? String.valueOf(memoryId) : null;
        if (value == null || value.isBlank()) return AiExecutionPolicy.defaultPolicy();
        if (scope == AgentScope.PLATFORM) {
            PlatformAiState state = PlatformAiStateStore.get(value);
            return state != null ? state.getExecutionPolicy() : AiExecutionPolicy.defaultPolicy();
        }
        PuppetNodeSession session = resolvePuppetSession(value);
        if (session == null) return AiExecutionPolicy.defaultPolicy();
        int separator = value.indexOf(':');
        String threadId = separator > 0 && separator < value.length() - 1
                ? value.substring(separator + 1) : null;
        AiThread thread = threadId != null
                ? session.getAiThread(threadId) : session.getActiveThread();
        return thread != null ? thread.getExecutionPolicy() : AiExecutionPolicy.defaultPolicy();
    }

    private PuppetNodeSession resolvePuppetSession(Object memoryId) {
        if (memoryId == null) return null;
        String value = String.valueOf(memoryId);
        int separator = value.indexOf(':');
        String sessionId = separator > 0 ? value.substring(0, separator) : value;
        return PuppetNodeSessionContainer.getSession(sessionId);
    }

    private boolean isAllowed(AiToolAccess.Level access, AiExecutionPolicy policy) {
        if (policy == null || policy.getUserId() == null || policy.getUserId().isBlank()) {
            return false;
        }
        return access != AiToolAccess.Level.ADMIN || policy.isAdmin();
    }

    private boolean isWaitingForUserInput(AgentScope scope, Object memoryId) {
        String value = memoryId != null ? String.valueOf(memoryId) : null;
        if (value == null || value.isBlank()) return false;
        if (scope == AgentScope.PLATFORM) {
            PlatformAiState state = PlatformAiStateStore.get(value);
            return state != null && state.isWaitingForUserInput();
        }
        PuppetNodeSession session = resolvePuppetSession(value);
        if (session == null) return false;
        int separator = value.indexOf(':');
        String threadId = separator > 0 && separator < value.length() - 1
                ? value.substring(separator + 1) : null;
        AiThread thread = threadId != null
                ? session.getAiThread(threadId) : session.getActiveThread();
        return thread != null && thread.isWaitingForUserInput();
    }

    private record SecuredTool(AiServiceTool tool,
                               AiToolAccess.Level access) {
    }
}
