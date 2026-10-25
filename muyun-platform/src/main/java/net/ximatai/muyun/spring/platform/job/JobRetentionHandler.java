package net.ximatai.muyun.spring.platform.job;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.Map;

@Component
public final class JobRetentionHandler implements JobHandler {
    private final ObjectProvider<JobService> jobs;
    public JobRetentionHandler(ObjectProvider<JobService> jobs) { this.jobs = jobs; }
    @Override public JobDefinition definition() {
        return new JobDefinition("platform.job-run-retention", "platform.job.type.runRetention", JobScope.PLATFORM,
                JobSchedule.fixedDelay(Duration.ofDays(1), Duration.ofMinutes(2)), "default", null,
                Duration.ofMinutes(10), 1, Duration.ZERO, input -> "retention", true, 3, true, null);
    }
    @Override public JobResult execute(JobContext context) {
        int removed = 0;
        for (int batch = 0; batch < 10; batch++) {
            context.checkCancelled();
            int count = context.transaction(() -> jobs.getObject().purgeExpiredBatch());
            removed += count;
            if (count < 200) break;
        }
        return removed == 2000 ? JobResult.partial("BATCH_LIMIT_REACHED", Map.of("removed", (long) removed))
                : JobResult.success("COMPLETE", Map.of("removed", (long) removed));
    }
}
