package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.platform.job.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/** Schedules the existing scan without widening its repository scope or changing workflow facts. */
@Component
public class WorkflowNodeOvertimeJob implements JobHandler {
    private final WorkflowNodeOvertimeScanService scanService;
    private final JobSchedule schedule;

    public WorkflowNodeOvertimeJob(WorkflowNodeOvertimeScanService scanService,
                                  @Value("${muyun.workflow.overtime-scan-delay-ms:300000}") long delayMillis,
                                  @Value("${muyun.workflow.overtime-scan-initial-delay-ms:60000}") long initialDelayMillis) {
        this.scanService = java.util.Objects.requireNonNull(scanService);
        this.schedule = JobSchedule.fixedDelay(Duration.ofMillis(delayMillis), Duration.ofMillis(initialDelayMillis));
    }

    @Override
    public JobDefinition definition() {
        return new JobDefinition("platform.workflow-overtime", "platform.job.type.workflowOvertime", JobScope.PLATFORM,
                schedule, "default", null, Duration.ofMinutes(10), 1, Duration.ofSeconds(15),
                parameters -> "workflow-overtime", true, 3, true, null);
    }

    @Override
    public JobResult execute(JobContext context) {
        context.checkCancelled();
        // Node updates are version-checked and idempotent; like the former scheduler, the scan manages its own
        // writes instead of holding the run row for the whole pass.
        var result = scanService.scan(null);
        return JobResult.success("COMPLETE", Map.of("scanned", (long) result.scannedCount(),
                "warned", (long) result.warnedCount(), "overdue", (long) result.overdueCount(),
                "updated", (long) result.updatedCount()));
    }
}
