package com.app.master.service.service.testing;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * A dedicated thread for test runs.
 *
 * <p>Separate from both the shared task executor and the anomaly scan executor. A
 * test run holds a subprocess for tens of minutes; putting that in a pool other
 * work shares would starve it.
 *
 * <p>One thread, no queue — the single-active-run index has already decided that a
 * second run must not start, so a queue could only hold work destined to be
 * rejected.
 */
@Configuration
public class TestRunnerExecutorConfig {

    @Bean(name = "testRunnerExecutor")
    public Executor testRunnerExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("TestRun-");
        // A shutdown mid-run should let the run close its own record rather than
        // leaving it RUNNING for ever.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.setRejectedExecutionHandler((r, e) -> {
            throw new RejectedExecutionException("A test run is already executing on this instance");
        });
        executor.initialize();
        return executor;
    }
}
