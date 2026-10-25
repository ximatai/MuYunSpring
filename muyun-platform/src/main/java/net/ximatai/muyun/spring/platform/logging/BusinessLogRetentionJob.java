package net.ximatai.muyun.spring.platform.logging;

import net.ximatai.muyun.spring.platform.job.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Platform scheduling adapter; the storage adapter retains its independent, idempotent cleanup boundary. */
public final class BusinessLogRetentionJob implements JobHandler {
    private static final Logger log = LoggerFactory.getLogger(BusinessLogRetentionJob.class);
    private final BusinessLogRetentionService service;
    private final BusinessLogRetentionExecutionLimits executionLimits;
    private final JobSchedule schedule;

    public BusinessLogRetentionJob(BusinessLogRetentionService service,
                                   BusinessLogRetentionExecutionLimits executionLimits,
                                   JobSchedule schedule) {
        this.service = Objects.requireNonNull(service);
        this.executionLimits = Objects.requireNonNull(executionLimits);
        this.schedule = Objects.requireNonNull(schedule);
    }

    @Override
    public JobDefinition definition() {
        return new JobDefinition("platform.business-log-retention", "platform.job.type.businessLogRetention", JobScope.PLATFORM,
                schedule, "default", null, Duration.ofHours(1), 1, Duration.ofSeconds(15),
                parameters -> "business-log-retention", true, 3, true, null);
    }

    @Override
    public JobResult execute(JobContext context) {
        context.checkCancelled();
        Map<String, Long> statistics = new LinkedHashMap<>();
        boolean partial = false;
        long skipped = 0;
        long policies = 0;
        for (BusinessLogRetentionRunResult run : service.purgeEnabled(executionLimits)) {
            log(run);
            policies++;
            var result = run.result();
            String prefix = run.eventType().name() + "_";
            statistics.put(prefix + "deleted", result.deletedCount());
            statistics.put(prefix + "batches", (long) result.executedBatches());
            statistics.put(prefix + result.status().name(), 1L);
            partial |= result.status() == net.ximatai.muyun.spring.ability.logging.BusinessLogRetentionResult.Status.BATCH_LIMIT_REACHED;
            if (result.status() == net.ximatai.muyun.spring.ability.logging.BusinessLogRetentionResult.Status.ALREADY_RUNNING) skipped++;
        }
        statistics.put("policies", policies);
        statistics.put("skipped", skipped);
        if (partial) return JobResult.partial("BATCH_LIMIT_REACHED", statistics);
        return JobResult.success(skipped > 0 ? "SKIPPED_ALREADY_RUNNING" : "COMPLETE", statistics);
    }

    private static void log(BusinessLogRetentionRunResult run) {
        var result = run.result();
        switch (result.status()) {
            case COMPLETE -> {
                if (result.deletedCount() > 0) {
                    log.info("Business-log retention completed: eventType={}, retentionDays={}, deletedCount={}, executedBatches={}, occurredBefore={}",
                            run.eventType(), run.retentionDays(), result.deletedCount(), result.executedBatches(),
                            result.occurredBefore());
                } else {
                    log.debug("Business-log retention found no expired events: eventType={}, occurredBefore={}",
                            run.eventType(), result.occurredBefore());
                }
            }
            case BATCH_LIMIT_REACHED -> log.warn(
                    "Business-log retention reached its batch limit: eventType={}, deletedCount={}, executedBatches={}, occurredBefore={}",
                    run.eventType(), result.deletedCount(), result.executedBatches(), result.occurredBefore());
            case ALREADY_RUNNING -> log.debug(
                    "Business-log retention skipped because another instance is running: eventType={}", run.eventType());
        }
    }
}
