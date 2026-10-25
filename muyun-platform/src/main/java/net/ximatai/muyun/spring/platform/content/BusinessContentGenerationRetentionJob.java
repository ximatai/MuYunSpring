package net.ximatai.muyun.spring.platform.content;

import net.ximatai.muyun.spring.platform.job.*;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.Map;

/** Topic history expires independently of run history and never removes translation facts. */
@Component
public final class BusinessContentGenerationRetentionJob implements JobHandler {
    private final BusinessContentGenerationStore store;
    private final Clock clock;
    public BusinessContentGenerationRetentionJob(BusinessContentGenerationStore store, Clock clock) {
        this.store = store; this.clock = clock;
    }
    public JobDefinition definition() {
        return new JobDefinition("platform.content-generation-retention", "platform.job.type.contentGenerationRetention",
                JobScope.PLATFORM, JobSchedule.fixedDelay(Duration.ofHours(1), Duration.ofMinutes(1)), "default", null,
                Duration.ofMinutes(5), 1, Duration.ZERO, input -> "retention", true, 3, true, null);
    }
    public JobResult execute(JobContext context) {
        context.checkCancelled();
        Instant now = clock.instant(); int count = 0;
        for (String id : store.expired(now)) {
            context.checkCancelled();
            count += context.transaction(() -> store.lock(id), () -> store.purgeExpired(id, now));
        }
        return JobResult.success("COMPLETE", Map.of("removed", (long) count));
    }
}
