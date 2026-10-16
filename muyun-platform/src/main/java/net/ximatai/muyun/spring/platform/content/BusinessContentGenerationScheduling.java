package net.ximatai.muyun.spring.platform.content;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.concurrent.atomic.AtomicLong;

/** A private topic executor keeps model latency out of Spring's shared workflow scheduler. */
@Component
final class BusinessContentGenerationScheduling implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(BusinessContentGenerationScheduling.class);
    private final BusinessContentGenerationService generations;
    private final long initialDelay;
    private final long delay;
    private ThreadPoolTaskScheduler scheduler;
    private final AtomicLong nextWarningAt = new AtomicLong();

    BusinessContentGenerationScheduling(BusinessContentGenerationService generations,
            @Value("${muyun.content.generation-initial-delay-ms:60000}") long initialDelay,
            @Value("${muyun.content.generation-delay-ms:1000}") long delay) {
        if (initialDelay < 0 || initialDelay > 300_000 || delay < 100 || delay > 60_000)
            throw new IllegalArgumentException("Invalid content generation scan interval");
        this.generations = generations; this.initialDelay = initialDelay; this.delay = delay;
    }

    @Override public synchronized void start() {
        if (scheduler != null) return;
        var executor = new ThreadPoolTaskScheduler();
        executor.setPoolSize(2); executor.setThreadNamePrefix("business-content-generation-");
        executor.setWaitForTasksToCompleteOnShutdown(false); executor.initialize();
        executor.scheduleWithFixedDelay(this::generate, Instant.now().plusMillis(initialDelay), Duration.ofMillis(delay));
        executor.scheduleWithFixedDelay(this::cleanup, Instant.now().plusSeconds(60), Duration.ofHours(1));
        scheduler = executor;
    }
    @Override public synchronized void stop() {
        if (scheduler != null) { scheduler.shutdown(); scheduler = null; }
    }
    @Override public synchronized boolean isRunning() { return scheduler != null; }

    private void generate() {
        try { generations.runOne(); }
        catch (RuntimeException unavailable) {
            // No provider messages, source text, scope payloads or credential references in operational logs.
            warn("scan", unavailable);
        }
    }

    private void cleanup() {
        try { generations.purgeExpired(); }
        catch (RuntimeException unavailable) { warn("retention", unavailable); }
    }
    private void warn(String operation, RuntimeException failure) {
        long now = System.currentTimeMillis(); long previous = nextWarningAt.get();
        if (now >= previous && nextWarningAt.compareAndSet(previous, now + 60_000))
            LOG.warn("Business content generation {} could not complete; durable work remains recoverable ({})",
                    operation, failure.getClass().getSimpleName());
    }
}
