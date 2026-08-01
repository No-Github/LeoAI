package org.leo.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI Agent 运行参数配置。
 *
 * <p>所有参数均可通过 {@code application.yml} 或环境变量覆盖，无需重新编译。
 *
 * <pre>
 * leo:
 *   ai:
 *     agent:
 *       puppet-node:
 *         main:
 *           max-parallel-tools: 5
 *           max-context-tokens: 180000        # 主 Agent token 滑动窗口上限
 *           tool-timeout-ms: 180000
 *           max-tool-result-chars: 12000
 *       platform:
 *         main:
 *           max-parallel-tools: 5
 *           max-context-tokens: 180000
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "leo.ai.agent")
public class AiAgentProperties {

    private final PuppetNodeConfig puppetNode = new PuppetNodeConfig();
    private final PlatformConfig platform = new PlatformConfig();

    // ── Getters ──────────────────────────────────────────────────────────────

    public PuppetNodeConfig getPuppetNode() { return puppetNode; }
    public PlatformConfig getPlatform() { return platform; }

    // ── PuppetNode ────────────────────────────────────────────────────────────

    public static class PuppetNodeConfig {
        private final MainAgentConfig main = new MainAgentConfig(5);

        public MainAgentConfig getMain() { return main; }
    }

    // ── Platform ──────────────────────────────────────────────────────────────

    public static class PlatformConfig {
        private final MainAgentConfig main = new MainAgentConfig(5);

        public MainAgentConfig getMain() { return main; }
    }

    // ── MainAgentConfig ───────────────────────────────────────────────────────

    public static class MainAgentConfig {
        private int maxParallelTools;
        /** 主 Agent token 滑动窗口上限，默认 180000（Claude 200k 窗口预留 system + tools 空间）。 */
        private int maxContextTokens = 180000;
        /** 单次工具执行硬超时。 */
        private long toolTimeoutMs = 180_000L;
        /** 发送给模型的单个工具结果字符上限，完整结果会进入短期归档。 */
        private int maxToolResultChars = 12_000;
        /** 写操作 tool-call ID 去重记录保留时间。 */
        private long toolIdempotencyTtlMs = 30 * 60 * 1000L;
        /** 大型工具结果归档保留时间。 */
        private long toolArchiveTtlMs = 30 * 60 * 1000L;

        public MainAgentConfig(int maxParallelTools) {
            this.maxParallelTools = maxParallelTools;
        }

        public int getMaxParallelTools()       { return maxParallelTools; }
        public int getMaxContextTokens()       { return maxContextTokens; }
        public long getToolTimeoutMs()         { return toolTimeoutMs; }
        public int getMaxToolResultChars()     { return maxToolResultChars; }
        public long getToolIdempotencyTtlMs()  { return toolIdempotencyTtlMs; }
        public long getToolArchiveTtlMs()      { return toolArchiveTtlMs; }

        public void setMaxParallelTools(int v)       { this.maxParallelTools = v; }
        public void setMaxContextTokens(int v)       { this.maxContextTokens = v; }
        public void setToolTimeoutMs(long v)         { this.toolTimeoutMs = Math.max(1L, v); }
        public void setMaxToolResultChars(int v)     { this.maxToolResultChars = Math.max(512, v); }
        public void setToolIdempotencyTtlMs(long v)  { this.toolIdempotencyTtlMs = Math.max(1_000L, v); }
        public void setToolArchiveTtlMs(long v)      { this.toolArchiveTtlMs = Math.max(1_000L, v); }
    }
}
