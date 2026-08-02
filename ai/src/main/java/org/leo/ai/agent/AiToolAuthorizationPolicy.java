package org.leo.ai.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderResult;
import dev.langchain4j.service.tool.ToolService;
import org.leo.ai.service.AiUserInputService;
import org.leo.ai.thread.AiConversationStoreService;
import org.leo.core.ai.AiRuntimeState;
import org.leo.core.entity.AiExecutionPolicy;
import org.leo.core.entity.AiUserInputRequest;
import org.leo.core.entity.User;
import org.leo.core.security.AccessPolicy;
import org.leo.core.session.PuppetNodeSession;
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
    private final AiToolCatalog toolCatalog;
    private final AgentRuntimeResolver runtimeResolver;
    private final AiToolExposurePolicy exposurePolicy;

    public AiToolAuthorizationPolicy(UserService userService) {
        this(userService, new AiToolExecutionBoundary(), null, null,
                new AiToolCatalog(), new AgentRuntimeResolver(), null);
    }

    public AiToolAuthorizationPolicy(UserService userService,
                                     AiToolExecutionBoundary executionBoundary,
                                     AiToolResultArchiveTools archiveTools) {
        this(userService, executionBoundary, archiveTools, null,
                new AiToolCatalog(), new AgentRuntimeResolver(), null);
    }

    public AiToolAuthorizationPolicy(UserService userService,
                                     AiToolExecutionBoundary executionBoundary,
                                     AiToolResultArchiveTools archiveTools,
                                     AiConversationStoreService conversationStore) {
        this(userService, executionBoundary, archiveTools, conversationStore,
                new AiToolCatalog(), new AgentRuntimeResolver(), null);
    }

    @Autowired
    public AiToolAuthorizationPolicy(UserService userService,
                                     AiToolExecutionBoundary executionBoundary,
                                     AiToolResultArchiveTools archiveTools,
                                     AiConversationStoreService conversationStore,
                                     AiToolCatalog toolCatalog,
                                     AgentRuntimeResolver runtimeResolver,
                                     AiToolExposurePolicy exposurePolicy) {
        this.userService = userService;
        this.executionBoundary = executionBoundary;
        this.conversationStore = conversationStore;
        this.toolCatalog = toolCatalog;
        this.runtimeResolver = runtimeResolver;
        this.exposurePolicy = exposurePolicy;
        this.archiveTools = archiveTools != null
                ? archiveTools
                : new AiToolResultArchiveTools(executionBoundary.archive());
    }

    public ToolProvider toolProvider(AgentScope scope, Object... toolObjects) {
        List<SecuredTool> securedTools = secureTools(scope, toolObjects);
        return new ToolProvider() {
            @Override
            public ToolProviderResult provideTools(
                    dev.langchain4j.service.tool.ToolProviderRequest request) {
                Object memoryId = request.chatMemoryId();
                AiExecutionPolicy policy = resolvePolicy(scope, memoryId);
                List<SecuredTool> permitted = securedTools.stream()
                        .filter(tool -> isAllowed(tool.access(), policy))
                        .toList();
                java.util.Set<String> exposedNames = exposurePolicy == null
                        ? null : exposurePolicy.visibleToolNames(scope, memoryId,
                        permitted.stream().map(tool -> tool.tool().name()).toList());
                List<AiServiceTool> visible = permitted.stream()
                        .filter(tool -> exposedNames == null
                                || exposedNames.contains(tool.tool().name()))
                        .map(SecuredTool::tool)
                        .toList();
                return new ToolProviderResult(visible);
            }

            @Override
            public boolean isDynamic() {
                return exposurePolicy != null;
            }
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
                AiToolDescriptor descriptor = toolCatalog.register(source, tool);
                secured.add(new SecuredTool(
                        wrap(scope, tool, access, descriptor), access));
            }
        }
        AiToolAccess.Level archiveAccess = AiToolAccess.Level.AUTHENTICATED;
        for (AiServiceTool tool : ToolService.findTools(archiveTools)) {
            AiToolDescriptor descriptor = toolCatalog.register(archiveTools, tool);
            secured.add(new SecuredTool(
                    wrap(scope, tool, archiveAccess, descriptor), archiveAccess));
        }
        return List.copyOf(secured);
    }

    private AiServiceTool wrap(AgentScope scope,
                               AiServiceTool tool,
                               AiToolAccess.Level access,
                               AiToolDescriptor descriptor) {
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
                AiRuntimeState runtime = runtimeResolver.resolve(scope, memoryId);
                AiRuntimeState.ToolLease lease;
                try {
                    lease = runtime != null
                            ? runtime.acquireToolLease(descriptor.exclusive()) : null;
                } catch (IllegalStateException terminal) {
                    throw AiToolException.userActionRequired(
                            "TERMINAL_CONTROL_ACTIVE",
                            "当前 Turn 已执行终止控制动作，不能继续调用工具。",
                            "立即结束本轮并等待用户操作。" );
                }
                try (lease) {
                    AiExecutionPolicy policy = AiToolContext.getExecutionPolicy();
                    if (!isAllowed(access, policy)) {
                        log.warn("拒绝 Agent 工具调用 scope={} tool={} userId={} privilege={}",
                                scope,
                                request != null ? request.name() : "unknown",
                                policy.getUserId(), policy.getPrivilege());
                        throw new SecurityException("当前身份无权执行该工具");
                    }
                    if (!descriptor.terminal()
                            && runtime != null && runtime.isWaitingForUserInput()) {
                        throw AiToolException.userActionRequired(
                                "USER_INPUT_PENDING",
                                "当前任务正在等待用户回答，不能继续执行其他工具。",
                                "停止工具调用并等待用户回答；不要自行假设用户意图。");
                    }
                    authorizeOperation(memoryId, descriptor, request);
                    AiToolContext.setToolDescriptor(descriptor);
                    ToolExecutionResult result = executionBoundary.execute(
                            scope, descriptor, delegate, request, context);
                    if (descriptor.terminal() && !result.isError() && runtime != null) {
                        runtime.markTerminalControl(descriptor.name());
                    }
                    return result;
                }
            }
        };
        return tool.toBuilder().toolExecutor(securedExecutor).build();
    }

    private void authorizeOperation(Object memoryId, AiToolDescriptor descriptor,
                                    ToolExecutionRequest request) {
        if (descriptor.operation() != AiToolOperation.DESTRUCTIVE) return;
        String toolName = descriptor.name();
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
        AiRuntimeState runtime = runtimeResolver.resolve(scope, memoryId);
        return runtime != null ? runtime.getActiveConfirmationRequestId() : null;
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
            PuppetNodeSession session = runtimeResolver.resolvePuppetSession(memoryId);
            if (!AccessPolicy.canAccessSession(session, user)) {
                return AiExecutionPolicy.defaultPolicy();
            }
        }
        return AiExecutionPolicy.from(user);
    }

    private AiExecutionPolicy runtimePolicy(AgentScope scope, Object memoryId) {
        AiRuntimeState runtime = runtimeResolver.resolve(scope, memoryId);
        return runtime != null ? runtime.getExecutionPolicy()
                : AiExecutionPolicy.defaultPolicy();
    }

    private boolean isAllowed(AiToolAccess.Level access, AiExecutionPolicy policy) {
        if (policy == null || policy.getUserId() == null || policy.getUserId().isBlank()) {
            return false;
        }
        return access != AiToolAccess.Level.ADMIN || policy.isAdmin();
    }

    private record SecuredTool(AiServiceTool tool,
                               AiToolAccess.Level access) {
    }
}
