package org.leo.web.controller.puppetnode.ai;

import jakarta.servlet.http.HttpServletRequest;
import org.leo.ai.tools.puppetnode.PlanTools;
import org.leo.core.entity.AiExecutionPolicy;
import org.leo.core.entity.AiPlan;
import org.leo.core.session.AiThread;
import org.leo.core.session.PuppetNodeSession;
import org.leo.core.util.ApiResponse;
import org.leo.web.dto.puppetnode.ai.*;
import org.leo.web.service.PuppetNodeAiThreadService;
import org.leo.web.service.AiTurnProtocolService;
import org.leo.web.service.AiTurnQueueService;
import org.leo.web.service.AiTurnCommandPayload;
import org.leo.web.exception.ApiException;
import org.leo.web.util.AiAttachmentPrompt;
import org.leo.web.util.AiEventSubscriptionService;
import org.leo.web.util.ControllerUtil;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

@RestController
@RequestMapping("/puppet-node/ai")
public class PuppetNodeAiController {

    private final PuppetNodeAiThreadService aiThreadService;
    private final PlanTools planTools;
    private final AiEventSubscriptionService eventSubscriptionService;
    private final AiTurnProtocolService turnProtocolService;
    private final AiTurnQueueService turnQueueService;

    public PuppetNodeAiController(PuppetNodeAiThreadService aiThreadService,
                                  PlanTools planTools,
                                  AiEventSubscriptionService eventSubscriptionService,
                                  AiTurnProtocolService turnProtocolService,
                                  AiTurnQueueService turnQueueService) {
        this.aiThreadService = aiThreadService;
        this.planTools = planTools;
        this.eventSubscriptionService = eventSubscriptionService;
        this.turnProtocolService = turnProtocolService;
        this.turnQueueService = turnQueueService;
    }

    // ─── SSE 流式对话 ─────────────────────────────────────────────────────────

    /** Codex 式 Turn 启动：立即返回 Turn，事件从独立订阅流获取。 */
    @PostMapping("/turn/start")
    public HashMap<String, Object> startTurn(
            @RequestBody PuppetAiChatRequest body,
            HttpServletRequest request) {
        String sessionId = requiredText(
                body != null ? body.sessionId() : null, "缺少 sessionId");
        String threadId = requiredText(
                body != null ? body.threadId() : null, "缺少 threadId");
        String message = requiredText(
                body != null ? body.message() : null, "缺少 message");
        PuppetNodeSession session = requiredSession(sessionId);
        session.touchLastActiveTime();

        PuppetNodeAiThreadService.ThreadResolution resolution =
                aiThreadService.ensureThreadReady(session, threadId, null);
        AiThread thread = resolution.thread();
        if (thread == null) {
            throw ApiException.notFound("线程不存在，threadId: " + threadId);
        }
        if (resolution.errorMessage() != null) {
            throw ApiException.badRequest(resolution.errorMessage());
        }
        AiExecutionPolicy policy = ControllerUtil.buildAiExecutionPolicy(request);
        String guardedMessage = AiAttachmentPrompt.appendTo(
                ControllerUtil.buildAiPolicyPrompt(policy, message), body.attachments());
        AiTurnCommandPayload command = AiTurnCommandPayload.create(
                AiTurnCommandPayload.SCOPE_PUPPET, sessionId,
                message, guardedMessage, body.configId(),
                body.reasoningEffort(),
                AiAttachmentPrompt.metadata(body.attachments()), policy);
        AiTurnProtocolService.Reservation reservation;
        try {
            reservation = turnProtocolService.begin(
                    threadId, body.clientUserMessageId(),
                    command.getScope(), command.toJson(),
                    message, body.attachments());
        } catch (RuntimeException error) {
            throw ApiException.badRequest(error.getMessage());
        }
        AiTurnProtocolService.TurnSnapshot turn = reservation.turn();
        if (reservation.reused()) {
            if (AiTurnProtocolService.STATUS_QUEUED.equals(turn.status())) {
                try {
                    turnQueueService.signal(threadId);
                } catch (RejectedExecutionException ignored) {
                    // 周期调度器继续恢复。
                }
            }
            return ApiResponse.success(Map.of("turn", turn.toMap()));
        }
        session.switchActiveThread(threadId);
        thread.touchLastActiveAt();
        try {
            turnQueueService.signal(threadId);
        } catch (RejectedExecutionException ignored) {
            // 命令已经持久化，周期调度器会继续领取。
        }
        return ApiResponse.success(Map.of("turn", turn.toMap()));
    }

    /** 请求中断指定 Turn；客户端继续订阅直到 turn/completed。 */
    @PostMapping("/turn/interrupt")
    public HashMap<String, Object> interruptTurn(
            @RequestBody AiTurnInterruptRequest body) {
        String sessionId = requiredText(
                body != null ? body.sessionId() : null, "缺少 sessionId");
        String threadId = requiredText(
                body != null ? body.threadId() : null, "缺少 threadId");
        String turnId = body != null ? body.turnId() : null;
        PuppetNodeSession session = requiredSession(sessionId);
        AiThread thread = aiThreadService.requireThread(session, threadId);
        try {
            AiTurnProtocolService.TurnSnapshot turn =
                    turnProtocolService.requestInterrupt(threadId, turnId);
            if (turn.id().equals(thread.getActiveTurnId())) {
                thread.stop();
            }
            return ApiResponse.success(Map.of("turn", turn.toMap()));
        } catch (RuntimeException error) {
            throw ApiException.badRequest(error.getMessage());
        }
    }

    // ─── 线程管理 ─────────────────────────────────────────────────────────────

    /**
     * 列出当前 puppet 的所有 AI 对话线程（合并内存 + 持久化，去重，按 lastActiveAt 倒序）。
     *
     * @param params {@code sessionId}
     */
    @RequestMapping("/thread/list")
    public HashMap<String, Object> listThreads(@RequestBody AiSessionRequest body) {
        PuppetNodeSession session = requiredSession(body != null ? body.sessionId() : null);
        return ApiResponse.success(aiThreadService.listThreads(session));
    }

    /**
     * 创建新的 AI 对话线程。
     *
     * @param params {@code sessionId}, {@code title}（可选）
     * @return {@code threadId}, {@code reconSummaryLoaded}, {@code grantedTypesCount}
     */
    @RequestMapping("/thread/create")
    public HashMap<String, Object> createThread(@RequestBody AiThreadCreateRequest body) {
        PuppetNodeSession session = requiredSession(body != null ? body.sessionId() : null);
        String title = body != null ? body.title() : null;
        Integer configId = body != null ? body.configId() : null;
        String mode = body != null ? body.mode() : null;
        return ApiResponse.success(aiThreadService.createThread(session, title, configId, mode));
    }

    /**
     * 删除指定 AI 对话线程（内存 + 持久化）。
     *
     * @param params {@code sessionId}, {@code threadId}
     */
    @RequestMapping("/thread/delete")
    public HashMap<String, Object> deleteThread(@RequestBody AiThreadRequest body) {
        PuppetNodeSession session = requiredSession(body != null ? body.sessionId() : null);
        String threadId = requiredText(body != null ? body.threadId() : null, "缺少 threadId");
        aiThreadService.deleteThread(session, threadId);
        return ApiResponse.success(true);
    }

    /**
     * 重命名指定 AI 对话线程。
     *
     * @param params {@code sessionId}, {@code threadId}, {@code title}
     */
    @RequestMapping("/thread/rename")
    public HashMap<String, Object> renameThread(@RequestBody AiThreadRenameRequest body) {
        PuppetNodeSession session = requiredSession(body != null ? body.sessionId() : null);
        String threadId = requiredText(body != null ? body.threadId() : null, "缺少 threadId");
        String title = requiredText(body != null ? body.title() : null, "缺少 title");
        aiThreadService.renameThread(session, threadId, title);
        return ApiResponse.success(true);
    }

    /**
     * 加载指定线程的历史消息（分页）。
     *
     * @param params {@code sessionId}, {@code threadId}, {@code offset}(int, 默认0),
     *               {@code limit}(int, 默认50)
     */
    @RequestMapping("/thread/messages")
    public HashMap<String, Object> threadMessages(@RequestBody AiThreadMessagesRequest body) {
        PuppetNodeSession session = requiredSession(body != null ? body.sessionId() : null);
        String threadId = requiredText(body != null ? body.threadId() : null, "缺少 threadId");
        Integer offset = body != null ? body.offset() : null;
        Integer limit = body != null ? body.limit() : null;
        return ApiResponse.success(aiThreadService.threadMessages(session, threadId, offset, limit));
    }

    /**
     * 获取指定线程最近 SSE 事件，用于切换会话或断线后补看执行过程。
     */
    @RequestMapping("/thread/events")
    public HashMap<String, Object> threadEvents(@RequestBody AiThreadEventsRequest body) {
        PuppetNodeSession session = requiredSession(body != null ? body.sessionId() : null);
        String threadId = requiredText(body != null ? body.threadId() : null, "缺少 threadId");
        Long afterSeq = body != null ? body.afterSeq() : null;
        Integer limit = body != null ? body.limit() : null;
        return ApiResponse.success(aiThreadService.threadEvents(session, threadId, afterSeq, limit));
    }

    /** 通过显式 sessionId + threadId 重新附着运行中的节点 AI 事件流。 */
    @PostMapping(value = "/thread/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter threadStream(@RequestBody AiThreadEventsRequest body) {
        PuppetNodeSession session =
                requiredSession(body != null ? body.sessionId() : null);
        String threadId = requiredText(
                body != null ? body.threadId() : null, "缺少 threadId");
        AiThread thread = aiThreadService.requireThread(session, threadId);
        return eventSubscriptionService.subscribe(
                "Puppet AI subscription", threadId, thread,
                body != null ? body.afterSeq() : null);
    }

    /**
     * 重置指定线程：清空 AI 状态（清除 LLM 上下文，保留持久化消息）。
     *
     * @param params {@code sessionId}, {@code threadId}, {@code configId}（可选）
     */
    @RequestMapping("/reset")
    public HashMap<String, Object> reset(@RequestBody AiThreadConfigRequest body) {
        PuppetNodeSession session = requiredSession(body != null ? body.sessionId() : null);
        String threadId = requiredText(body != null ? body.threadId() : null, "缺少 threadId");
        Integer configId = body != null ? body.configId() : null;
        return ApiResponse.success(aiThreadService.resetThread(session, threadId, configId));
    }

    /**
     * 热切换 AI 通道。
     *
     * @param params {@code sessionId}, {@code threadId}, {@code configId}
     */
    @RequestMapping("/switchChannel")
    public HashMap<String, Object> switchChannel(@RequestBody AiThreadConfigRequest body) {
        PuppetNodeSession session = requiredSession(body != null ? body.sessionId() : null);
        String threadId = requiredText(body != null ? body.threadId() : null, "缺少 threadId");
        Integer configId = body != null ? body.configId() : null;
        aiThreadService.switchChannel(session, threadId, configId);
        return ApiResponse.success(true);
    }

    @RequestMapping("/thread/switchMode")
    public HashMap<String, Object> switchMode(@RequestBody AiThreadModeRequest body) {
        PuppetNodeSession session = requiredSession(body != null ? body.sessionId() : null);
        String threadId = requiredText(body != null ? body.threadId() : null, "缺少 threadId");
        String mode = body != null ? body.mode() : null;
        return ApiResponse.success(aiThreadService.switchMode(session, threadId, mode));
    }

    // ─── 任务计划查询 ─────────────────────────────────────────────────────────

    /**
     * 获取指定线程的当前活跃任务计划。
     *
     * @param params {@code sessionId}, {@code threadId}
     */
    @RequestMapping("/thread/plan")
    public HashMap<String, Object> threadPlan(@RequestBody AiThreadRequest body) {
        PuppetNodeSession session = requiredSession(body != null ? body.sessionId() : null);
        String threadId = requiredText(body != null ? body.threadId() : null, "缺少 threadId");
        AiThread thread = aiThreadService.requireThread(session, threadId);
        AiPlan plan = thread.getCurrentPlan();
        return ApiResponse.success(plan);
    }

    /**
     * 获取指定线程的所有历史任务计划（按创建时间升序）。
     *
     * @param params {@code sessionId}, {@code threadId}
     */
    @RequestMapping("/thread/plans")
    public HashMap<String, Object> threadPlans(@RequestBody AiThreadRequest body) {
        PuppetNodeSession session = requiredSession(body != null ? body.sessionId() : null);
        String threadId = requiredText(body != null ? body.threadId() : null, "缺少 threadId");
        AiThread thread = aiThreadService.requireThread(session, threadId);
        List<AiPlan> history = thread.getPlanHistory();
        return ApiResponse.success(history);
    }

    // ─── 计划预批准 ─────────────────────────────────────────────────────────

    /**
     * 预批准指定计划步骤，步骤执行时高影响工具调用跳过用户确认。
     *
     * @param body {@code sessionId}, {@code threadId}, {@code stepIndex}（null 则预批准全部）
     */
    @RequestMapping("/plan/preApprove")
    public HashMap<String, Object> preApprovePlanStep(@RequestBody AiPlanPreApproveRequest body) {
        String sessionId = requiredText(body != null ? body.sessionId() : null, "缺少 sessionId");
        String threadId = requiredText(body != null ? body.threadId() : null, "缺少 threadId");
        requiredSession(sessionId);
        if (body.stepIndex() != null) {
            return ApiResponse.success(planTools.preApproveStep(sessionId, threadId, body.stepIndex()));
        } else {
            return ApiResponse.success(planTools.preApproveAllSteps(sessionId, threadId));
        }
    }

    private PuppetNodeSession requiredSession(String sessionId) {
        return ControllerUtil.getPuppetNodeSession(requiredText(sessionId, "缺少 sessionId"));
    }

    private String requiredText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw org.leo.web.exception.ApiException.badRequest(message);
        }
        return value.trim();
    }
}
