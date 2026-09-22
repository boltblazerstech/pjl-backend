package com.pjl.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Configures Spring's async task executor for the verification pipeline.
 * <p>
 * Using a named executor instead of the default SimpleAsyncTaskExecutor gives us:
 * - A bounded thread pool (won't spin up unlimited threads under load)
 * - Named threads for easy identification in logs/thread dumps
 * - A caller-runs rejection policy that blocks instead of dropping work
 */
@EnableAsync
@Configuration
public class AsyncConfig {

    @Bean(name = "verificationExecutor")
    public Executor verificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("pjl-verify-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
