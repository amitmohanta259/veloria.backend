package com.app.master.service.service.engineering;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * A dedicated executor for anomaly scans.
 *
 * <p><b>Not the shared {@code taskExecutor}.</b> That bean serves other features
 * with a 500-deep queue, and a multi-minute database scan parked in it would
 * contend with them — while a 500-deep queue is the wrong shape for work that may
 * only ever have one instance in flight.
 *
 * <p>One thread, no queue. The advisory lock already guarantees a single active
 * scan, so a queue could only hold work that the lock has already decided must not
 * run; making the rejection explicit is better than letting a request sit in a
 * queue behind a scan that will reject it anyway.
 */
@Configuration
public class EngineeringExecutorConfig {

    @Bean(name = "engineeringScanExecutor")
    public Executor engineeringScanExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        // Zero capacity: a submission either starts now or is refused.
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("EngScan-");
        // A shutdown mid-scan should let the scan close its own record rather than
        // leaving it RUNNING forever.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        // Surfaced, never silently dropped.
        executor.setRejectedExecutionHandler((r, e) -> {
            throw new RejectedExecutionException(
                    "An anomaly scan is already executing on this instance");
        });
        executor.initialize();
        return executor;
    }
}
