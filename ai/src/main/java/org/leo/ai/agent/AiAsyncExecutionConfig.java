package org.leo.ai.agent;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * AI 辅助任务专用执行器。
 *
 * <p>不能让未限定执行器的 {@code @Async} 回退到 Spring 的 TaskScheduler：
 * 后台模型请求可能阻塞数分钟，一旦占住定时调度线程，执行租约将无法续期。
 */
@Configuration
public class AiAsyncExecutionConfig {

    public static final String BACKGROUND_EXECUTOR = "aiBackgroundTaskExecutor";

    @Bean(name = BACKGROUND_EXECUTOR)
    public ThreadPoolTaskExecutor aiBackgroundTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(128);
        executor.setKeepAliveSeconds(60);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setThreadNamePrefix("ai-background-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}
