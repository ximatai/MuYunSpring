package net.ximatai.muyun.spring.starter.configuration.platform;

import net.ximatai.muyun.spring.platform.logging.BusinessLogRetentionExecutionLimits;
import net.ximatai.muyun.spring.platform.logging.BusinessLogRetentionRunResult;
import net.ximatai.muyun.spring.platform.logging.BusinessLogRetentionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Opt-in scheduler for one bounded, cluster-safe business-log retention run. */
public final class BusinessLogRetentionScheduler {
    private static final Logger log = LoggerFactory.getLogger(BusinessLogRetentionScheduler.class);
    private final BusinessLogRetentionService service;
    private final BusinessLogRetentionExecutionLimits executionLimits;

    public BusinessLogRetentionScheduler(BusinessLogRetentionService service,
                                         BusinessLogRetentionExecutionLimits executionLimits) {
        this.service = service;
        this.executionLimits = executionLimits;
    }

    @Scheduled(
            fixedDelayString = "${muyun.platform.business-log.retention.scan-delay:PT24H}",
            initialDelayString = "${muyun.platform.business-log.retention.initial-delay:PT1M}"
    )
    public void purgeScheduled() {
        for (BusinessLogRetentionRunResult run : service.purgeEnabled(executionLimits)) {
            log(run);
        }
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
