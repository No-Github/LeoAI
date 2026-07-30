package org.leo.ai.agent;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.model.TokenCountEstimator;
import org.leo.ai.channel.AiModelConfigService;
import org.leo.ai.channel.DelegatingChatModel;
import org.leo.ai.channel.DelegatingStreamingChatModel;
import org.leo.ai.config.AiAgentProperties;
import org.leo.ai.service.SkillRegistryService;
import org.leo.ai.tools.platform.SkillActivationTools;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LangChain4j Agent 配置。
 *
 * <p>使用 {@link DelegatingStreamingChatModel} / {@link DelegatingChatModel} 代理层，
 * 支持运行时通过 {@link org.leo.ai.channel.DynamicModelProvider#refresh()} 热切换底层模型。
 *
 * <p>模型代理启动时为空，由 DynamicModelProvider 从数据库加载激活 Provider。
 *
 * <p>线程池架构：
 * <ul>
 *   <li>{@code rawAiToolExecutor} — 底层固定 12 线程池，承载所有工具执行</li>
 *   <li>{@code aiToolExecutor}    — 主 Agent 工具执行器（限流包装，maxParallelTools=5）</li>
 * </ul>
 *
 * <p>所有工具直接附着到主 Agent，无子 Agent 调度层。
 * 纯 OS 命令包装工具已移除，统一通过 exec 工具替代。
 */
@Configuration
@EnableAsync
public class AgentConfig {

    private static final int TOOL_EXECUTOR_THREADS = 12;

    // ── 注入依赖 ──────────────────────────────────────────────────────────────

    private final AiAgentProperties agentProps;
    private final AiModelConfigService modelConfigService;

    public AgentConfig(AiAgentProperties agentProps, AiModelConfigService modelConfigService) {
        this.agentProps = agentProps;
        this.modelConfigService = modelConfigService;
    }

    // ── 线程池 ────────────────────────────────────────────────────────────────

    /**
     * 底层工具执行线程池：固定 12 线程，供 destroy 生命周期管理。
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService rawAiToolExecutor() {
        AtomicInteger counter = new AtomicInteger(1);
        return Executors.newFixedThreadPool(TOOL_EXECUTOR_THREADS, runnable -> {
            Thread thread = new Thread(runnable, "ai-tool-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 主 Agent 工具执行器：限流包装，最大并发数由 maxParallelTools 配置控制。
     */
    @Bean
    @Primary
    public ExecutorService aiToolExecutor(@Qualifier("rawAiToolExecutor") ExecutorService raw) {
        int maxParallel = agentProps.getPuppetNode().getMain().getMaxParallelTools();
        return new ThrottledExecutorService(raw, maxParallel);
    }

    // ── Token 估算与对话记忆 ─────────────────────────────────────────────────

    /**
     * 轻量级字符 token 估算器，无网络调用。
     * 用于 {@link TokenWindowChatMemory} 的滑动窗口淘汰判定和压缩触发判断。
     */
    @Bean
    public TokenCountEstimator charBasedTokenEstimator() {
        return new CharBasedTokenEstimator();
    }

    /**
     * 上下文压缩服务：在对话历史接近窗口上限时自动将旧消息压缩为摘要。
     */
    @Bean
    public ContextCompressionService contextCompressionService(
            DelegatingChatModel chatModel, TokenCountEstimator tokenEstimator) {
        return new ContextCompressionService(chatModel, tokenEstimator);
    }

    /**
     * 主 Agent 对话记忆：自动适配模型上下文窗口大小。
     *
     * <p>预算规则：
     * <ol>
     *   <li>使用当前线程实际选择模型的上下文硬上限</li>
     *   <li>预留 system prompt、工具定义和输出空间</li>
     *   <li>配置文件 {@code max-context-tokens} 作为系统侧上限，不得放大模型窗口</li>
     * </ol>
     *
     * <p>小窗口（&lt;=96K）使用 {@link TokenWindowChatMemory}（基于 token 精确淘汰）；
     * 大窗口（&gt;96K）使用 {@link MessageWindowChatMemory}（按消息条数淘汰），
     * 避免 {@link CharBasedTokenEstimator} 在百万 token 量级下的累积误差。
     *
     * <p>压缩由 {@link CompressingChatMemory#messages()} 在读取记忆视图时触发，
     * 仅当窗口 &gt;=100K 且当前 token 数 &gt; 80% 阈值时执行。
     */
    @Bean
    @Primary
    public ChatMemoryProvider chatMemoryProvider(AiChatMemoryProviderFactory memoryProviderFactory) {
        return memoryProviderFactory.createPuppetProvider(modelConfigService.getActiveContextWindowTokens());
    }

    // ── 模型 Bean ────────────────────────────────────────────────────────────

    /**
     * 代理流式模型 Bean。Agent 绑定此代理，底层实例可热切换。
     */
    @Bean
    public DelegatingStreamingChatModel delegatingStreamingChatModel() {
        return new DelegatingStreamingChatModel();
    }

    /**
     * 代理非流式模型 Bean。辅助服务（摘要、情报提取）注入此 Bean。
     */
    @Bean
    public DelegatingChatModel delegatingChatModel() {
        return new DelegatingChatModel();
    }

    // ── Skill 工具 Bean ───────────────────────────────────────────────────────

    @Bean
    public SkillActivationTools puppetNodeSkillActivationTools(SkillRegistryService skillRegistry) {
        return new SkillActivationTools(skillRegistry, SkillRegistryService.SCOPE_PUPPET_NODE);
    }

    @Bean
    public SkillActivationTools platformSkillActivationTools(SkillRegistryService skillRegistry) {
        return new SkillActivationTools(skillRegistry, SkillRegistryService.SCOPE_PLATFORM);
    }

    // ── 主 Agent（默认 Bean，按激活通道热切换；会话级 Agent 由 AiAgentFactory 构建）────

    @Bean
    public PuppetNodeAgent puppetNodeAgent(DelegatingStreamingChatModel streamingModel,
                                           DelegatingChatModel chatModel,
                                           AiAgentFactory agentFactory) {
        return agentFactory.createPuppetNodeAgent(streamingModel, chatModel);
    }

    // ── Platform Agent ───────────────────────────────────────────────────────

    @Bean
    public PlatformAgent platformAgent(DelegatingStreamingChatModel model,
                                       AiAgentFactory agentFactory) {
        return agentFactory.createPlatformAgent(model);
    }
}
