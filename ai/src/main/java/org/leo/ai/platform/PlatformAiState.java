package org.leo.ai.platform;

import org.leo.ai.agent.AiStateAccessor;
import org.leo.core.ai.AiEventStreamRuntime;
import org.leo.core.entity.AiExecutionPolicy;
import org.leo.core.entity.AiPlan;
import org.leo.core.entity.AiRuntimeStats;
import org.leo.core.entity.AiSseEvent;

import java.util.List;
import java.util.Collections;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 平台侧 AI 运行时状态，同时实现 {@link AiStateAccessor}。
 *
 * <p>生命周期由 {@link PlatformAiStateStore} 管理，绑定到 HTTP Session。
 */
public class PlatformAiState implements AiStateAccessor, AiEventStreamRuntime {

    public static final String STATUS_IDLE = "idle";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_CANCELLED = "cancelled";

    private final String stateId;
    private final long createdAt;
    private volatile long lastActiveAt;
    private volatile Integer aiConfigId;
    private volatile String mode = "auto";
    private final AiRuntimeStats runtimeStats = new AiRuntimeStats();
    private volatile AiExecutionPolicy executionPolicy = AiExecutionPolicy.defaultPolicy();
    private final AtomicInteger turnCount = new AtomicInteger(0);
    private static final int MAX_TURNS_WARN = 25;
    private volatile Thread executingThread;
    private final AtomicBoolean executionClaimed = new AtomicBoolean(false);
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private volatile String runStatus = STATUS_IDLE;
    private volatile String activeTurnId;
    private volatile String activeItemId;
    private volatile String activeRunId;
    private volatile String activeLeaseToken;
    private volatile String stopReason;
    private volatile Runnable stopCallback;

    private final LinkedBlockingQueue<AiSseEvent> sseEventQueue = new LinkedBlockingQueue<>();
    private final AtomicLong sseEventSeq = new AtomicLong(0);
    private volatile long currentRunStartSeq;
    private volatile Consumer<AiSseEvent> eventJournalSink = event -> {};

    private final List<AiPlan> planHistory = new CopyOnWriteArrayList<>();

    public PlatformAiState(String stateId) {
        this.stateId = stateId;
        this.createdAt = System.currentTimeMillis();
        this.lastActiveAt = this.createdAt;
    }

    @Override
    public AiRuntimeStats getRuntimeStats() { return runtimeStats; }

    @Override
    public AiExecutionPolicy getExecutionPolicy() {
        return executionPolicy != null ? executionPolicy : AiExecutionPolicy.defaultPolicy();
    }

    @Override public boolean isValid() { return true; }

    public boolean claimExecution() {
        boolean claimed = executionClaimed.compareAndSet(false, true);
        if (claimed) {
            currentRunStartSeq = sseEventSeq.get();
            stopRequested.set(false);
            stopReason = null;
            runStatus = STATUS_RUNNING;
        }
        return claimed;
    }

    public void markExecuting(Thread thread) {
        if (!executionClaimed.get()) claimExecution();
        this.executingThread = thread;
    }

    public void clearExecuting() {
        this.executingThread = null;
        this.stopCallback = null;
        executionClaimed.set(false);
        stopRequested.set(false);
    }

    @Override public boolean isStopRequested() { return stopRequested.get(); }
    public boolean isExecuting() { return executionClaimed.get() || executingThread != null; }
    public String getRunStatus() { return runStatus; }
    @Override public String getActiveTurnId() { return activeTurnId; }
    @Override public void bindActiveTurnId(String turnId) { this.activeTurnId = turnId; }
    @Override public String getActiveItemId() { return activeItemId; }
    @Override public void bindActiveItemId(String itemId) { this.activeItemId = itemId; }
    @Override public String getActiveRunId() { return activeRunId; }
    @Override public void bindActiveRunId(String runId) { this.activeRunId = runId; }
    @Override public String getActiveLeaseToken() { return activeLeaseToken; }
    @Override public void bindActiveLeaseToken(String leaseToken) { this.activeLeaseToken = leaseToken; }
    public String getStopReason() { return stopReason; }

    public void markCompleted() { runStatus = STATUS_COMPLETED; }
    public void markFailed() { runStatus = STATUS_FAILED; }
    public void markCancelled() { runStatus = STATUS_CANCELLED; }

    public void stopGeneration() { stopGeneration("用户手动停止"); }

    public void stopGeneration(String reason) {
        stopRequested.set(true);
        stopReason = reason != null && !reason.isBlank() ? reason : "已停止";
        runStatus = STATUS_CANCELLED;
        Runnable cb = stopCallback;
        if (cb != null) { try { cb.run(); } catch (Exception ignored) {} }
        Thread thread = executingThread;
        if (thread != null) thread.interrupt();
        if (stopReason != null && !stopReason.isBlank()) offerWarnMessage(stopReason);
    }

    public void setStopCallback(Runnable callback) { this.stopCallback = callback; }

    public String getStateId() { return stateId; }
    public long getCreatedAt() { return createdAt; }
    public long getLastActiveAt() { return lastActiveAt; }
    public void touchLastActiveAt() { this.lastActiveAt = System.currentTimeMillis(); }
    public Integer getAiConfigId() { return aiConfigId; }
    public String getMode() { return mode != null ? mode : "auto"; }
    public void setMode(String mode) { this.mode = mode == null || mode.isBlank() ? "auto" : mode; }
    public void setAiConfigId(Integer aiConfigId) { this.aiConfigId = aiConfigId; }
    public void setExecutionPolicy(AiExecutionPolicy ep) { this.executionPolicy = ep != null ? ep : AiExecutionPolicy.defaultPolicy(); }

    @Override public LinkedBlockingQueue<AiSseEvent> getAiSseEventQueue() { return sseEventQueue; }

    public AiSseEvent offerSseEvent(String name, Object data) {
        AiSseEvent event = recordSseEvent(name, data); sseEventQueue.offer(event); return event;
    }

    public AiSseEvent recordSseEvent(String name, Object data) { return recordSseEvent(name, data, null); }

    public AiSseEvent recordSseEvent(String name, Object data, String subagentInvocationId) {
        AiSseEvent event = new AiSseEvent(sseEventSeq.incrementAndGet(),
                System.currentTimeMillis(), name, data, subagentInvocationId,
                activeTurnId, activeItemId, activeRunId);
        eventJournalSink.accept(event);
        return event;
    }

    @Override
    public void configureEventJournal(long persistedLastSeq,
                                      Consumer<AiSseEvent> eventSink) {
        sseEventSeq.accumulateAndGet(Math.max(0L, persistedLastSeq), Math::max);
        eventJournalSink = eventSink != null ? eventSink : event -> {};
    }

    public long getLastSseEventSeq() { return sseEventSeq.get(); }

    @Override public long getCurrentRunStartSeq() { return currentRunStartSeq; }

    public void clearSseEvents() {
        sseEventQueue.clear();
        currentRunStartSeq = sseEventSeq.get();
        activeTurnId = null;
        activeItemId = null;
        activeRunId = null;
        activeLeaseToken = null;
    }

    public String incrementAndCheckTurnCount() {
        int count = turnCount.incrementAndGet();
        if (count >= MAX_TURNS_WARN)
            return "对话已进行 " + count + " 轮，上下文接近上限，建议点击「新建会话」以保持最佳效果。";
        return null;
    }

    public void resetTurnCount() {
        turnCount.set(0);
        executingThread = null; executionClaimed.set(false);
        runStatus = STATUS_IDLE; stopReason = null; stopRequested.set(false);
        activeTurnId = null;
        clearSseEvents();
    }

    @Override
    public void offerWarnMessage(String message) {
        if (message != null && !message.isBlank()) offerSseEvent("warn", message);
    }

    @Override public void setCurrentPlan(AiPlan plan) {
        if (plan != null) { planHistory.add(plan); offerSseEvent("plan", plan); }
    }

    @Override public AiPlan getCurrentPlan() {
        return planHistory.isEmpty() ? null : planHistory.get(planHistory.size() - 1);
    }

    @Override public List<AiPlan> getPlanHistory() { return Collections.unmodifiableList(planHistory); }

    @Override public void notifyPlanUpdated() {
        AiPlan plan = getCurrentPlan(); if (plan != null) offerSseEvent("plan", plan);
    }
}
