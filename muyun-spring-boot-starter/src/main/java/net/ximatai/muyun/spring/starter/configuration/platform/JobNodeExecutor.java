package net.ximatai.muyun.spring.starter.configuration.platform;

import net.ximatai.muyun.spring.platform.job.JobRegistry;
import net.ximatai.muyun.spring.platform.job.JobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** One local pool per channel; leases and due-plan scanning never share handler threads. */
public final class JobNodeExecutor implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(JobNodeExecutor.class);
    private final JobService jobs;
    private final MuYunSpringJobProperties properties;
    private final String nodeId = UUID.randomUUID().toString();
    private final Map<String, Channel> channels = new LinkedHashMap<>();
    private final AtomicLong nextWarning = new AtomicLong();
    private final AtomicLong nextOwner = new AtomicLong();
    private volatile boolean running;
    private ScheduledExecutorService scanner;
    private ScheduledThreadPoolExecutor heartbeats;

    public JobNodeExecutor(JobService jobs, JobRegistry registry, MuYunSpringJobProperties properties) {
        this.jobs = Objects.requireNonNull(jobs);
        this.properties = Objects.requireNonNull(properties);
        properties.validate();
        registry.definitions().forEach(definition -> {
            if (!properties.getChannels().containsKey(definition.channel()))
                throw new IllegalArgumentException("Unconfigured job channel: " + definition.channel());
        });
        properties.getChannels().forEach((name, config) -> channels.put(name, new Channel(name, config)));
        jobs.onEnqueued(this::wake);
    }

    @Override public boolean isAutoStartup() { return false; }
    @Override public boolean isRunning() { return running; }

    /** PlatformBootstrapRunner has initialized schema and declarations before this event. */
    @EventListener(ApplicationReadyEvent.class)
    public void applicationReady() { start(); }

    @Override public synchronized void start() {
        if (running) return;
        if (channels.values().stream().anyMatch(channel -> channel.pool != null && !channel.pool.isTerminated()))
            throw new IllegalStateException("Previous background job workers have not stopped");
        jobs.registerDefaults();
        running = true;
        int workers = channels.values().stream().mapToInt(channel -> channel.threads).sum();
        // There is exactly one periodic renewal per worker, so neither work nor a backlog accumulates.
        heartbeats = new ScheduledThreadPoolExecutor(workers, runnable -> thread(runnable, "job-lease"));
        heartbeats.setRemoveOnCancelPolicy(true);
        if (properties.isScheduledEnabled()) {
            scanner = Executors.newSingleThreadScheduledExecutor(runnable -> thread(runnable, "job-scan"));
            scanner.scheduleWithFixedDelay(() -> safely(jobs::scan), 0,
                    properties.getScanInterval().toMillis(), TimeUnit.MILLISECONDS);
        }
        channels.values().forEach(Channel::start);
    }

    @Override public synchronized void stop() {
        if (!running) return;
        running = false;
        if (scanner != null) scanner.shutdownNow();
        channels.values().forEach(channel -> { channel.wake(); channel.pool.shutdown(); });
        long deadline = System.nanoTime() + properties.getShutdownGrace().toNanos();
        for (Channel channel : channels.values()) {
            try {
                channel.pool.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        // Keep heartbeats active during the grace period; fence residual handlers before returning.
        heartbeats.shutdownNow();
        channels.values().forEach(channel -> channel.workers.forEach(worker -> safely(() -> jobs.releaseNode(worker.ownerId))));
        channels.values().forEach(channel -> channel.pool.shutdownNow());
    }

    private void wake(String channel) {
        Channel local = channels.get(channel);
        if (local != null) local.wake();
    }

    private void safely(Runnable work) {
        try { work.run(); }
        catch (RuntimeException failure) {
            long now = System.currentTimeMillis(), previous = nextWarning.get();
            if (now >= previous && nextWarning.compareAndSet(previous, now + 60_000))
                log.warn("Background job maintenance could not complete ({})", failure.getClass().getSimpleName());
        }
    }

    private static Thread thread(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private final class Channel {
        private final String name;
        private final int threads;
        private final Duration claimInterval;
        private final Object signal = new Object();
        private ExecutorService pool;
        private final List<Worker> workers = new ArrayList<>();
        private long generation;

        private Channel(String name, MuYunSpringJobProperties.Channel config) {
            this.name = name; threads = config.getThreads(); claimInterval = config.getClaimInterval();
        }

        private void start() {
            pool = Executors.newFixedThreadPool(threads, runnable -> thread(runnable, "job-" + name));
            workers.clear();
            for (int index = 0; index < threads; index++) {
                var worker = new Worker(); workers.add(worker);
                heartbeats.scheduleWithFixedDelay(() -> safely(() -> jobs.renewNode(worker.ownerId)), 1, 1, TimeUnit.SECONDS);
                pool.submit(worker::claim);
            }
        }

        private void wake() {
            synchronized (signal) { generation++; signal.notifyAll(); }
        }

        private final class Worker {
            private final String ownerId = nodeId + ":" + nextOwner.incrementAndGet();

            private void claim() {
                try {
                    while (running && !Thread.currentThread().isInterrupted()) {
                        long observed;
                        synchronized (signal) { observed = generation; }
                        try { if (jobs.runOne(name, ownerId, () -> running)) continue; }
                        catch (RuntimeException failure) { safely(() -> { throw failure; }); }
                        synchronized (signal) {
                            if (!running || generation != observed) continue;
                            try { signal.wait(claimInterval.toMillis()); }
                            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                        }
                    }
                } finally {
                    // A database claim may finish after stop's first release snapshot.
                    if (!running) safely(() -> jobs.releaseNode(ownerId));
                }
            }
        }
    }
}
