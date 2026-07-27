package org.leo.core.ai;

import org.leo.core.entity.AiSseEvent;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

/**
 * 可重放 AI 事件流所需的统一运行时契约。
 *
 * <p>平台与节点运行时只保存事件状态；具体使用 SSE、WebSocket 或其他传输由上层适配器决定。
 */
public interface AiEventStreamRuntime extends AiTurnRuntime {

    LinkedBlockingQueue<AiSseEvent> getAiSseEventQueue();

    AiSseEvent recordSseEvent(String name, Object data);

    AiSseEvent recordSseEvent(String name, Object data, String subagentInvocationId);

    long getLastSseEventSeq();

    /**
     * 绑定持久化事件接收器，并把内存序号推进到数据库中的最新序号。
     * 重复绑定必须保持序号单调，不能让新事件覆盖旧游标。
     */
    void configureEventJournal(long persistedLastSeq, Consumer<AiSseEvent> eventSink);

    /** 当前 Turn 开始前的事件序号；新订阅从这里开始重放，避免重复历史 Turn。 */
    long getCurrentRunStartSeq();

    /** 当前或最近一次执行的 Turn ID。 */
    String getActiveTurnId();

    /** 在执行权抢占后、后台任务提交前绑定协议层 Turn ID。 */
    void bindActiveTurnId(String turnId);

    /** 当前 Turn 的 assistant message Item ID。 */
    String getActiveItemId();

    /** 将后续模型事件绑定到稳定 assistant Item。 */
    void bindActiveItemId(String itemId);

    /** 当前 Turn 对应的持久化 Run ID。 */
    String getActiveRunId();

    /** beginTurn 成功后绑定 Run，供后续事件日志建立关联。 */
    void bindActiveRunId(String runId);

    /** 当前执行权对应的数据库租约令牌，用作所有执行期写入的 fencing token。 */
    String getActiveLeaseToken();

    /** 抢占数据库租约后绑定令牌；同一次执行生命周期内不得降级为 null。 */
    void bindActiveLeaseToken(String leaseToken);

    String getRunStatus();

    boolean isExecuting();
}
