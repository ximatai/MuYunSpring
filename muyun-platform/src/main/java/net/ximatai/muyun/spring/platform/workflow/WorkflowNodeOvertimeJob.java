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
        var now = java.time.Instant.now();
        String cursor = null;
        int scanned = 0, warned = 0, overdue = 0;
        do {
            String afterId = cursor;
            var batch = context.transaction(() -> scanService.scanBatch(now, afterId));
            scanned += batch.result().scannedCount();
            warned += batch.result().warnedCount();
            overdue += batch.result().overdueCount();
            cursor = batch.nextCursor();
        } while (cursor != null);
        var result = new WorkflowNodeOvertimeScanResult(scanned, warned, overdue);
        return JobResult.success("COMPLETE", Map.of("scanned", (long) result.scannedCount(),
                "warned", (long) result.warnedCount(), "overdue", (long) result.overdueCount(),
                "updated", (long) result.updatedCount()));
    }
}
