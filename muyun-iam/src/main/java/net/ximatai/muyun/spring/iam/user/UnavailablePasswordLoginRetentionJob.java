package net.ximatai.muyun.spring.iam.user;

import net.ximatai.muyun.spring.platform.job.*;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** Reclaims anonymous login names through the standard platform job runtime. */
@Component
public class UnavailablePasswordLoginRetentionJob implements JobHandler {
    private final UnavailablePasswordLoginService failures;
    private final Clock clock;
    public UnavailablePasswordLoginRetentionJob(UnavailablePasswordLoginService failures, Clock clock) {
        this.failures = Objects.requireNonNull(failures);
        this.clock = Objects.requireNonNull(clock);
    }
    public JobDefinition definition() {
        return new JobDefinition("iam.unavailable-password-login-retention", "iam.job.type.unavailablePasswordLoginRetention",
                JobScope.PLATFORM, JobSchedule.fixedDelay(Duration.ofHours(1), Duration.ofMinutes(1)),
                "default", null, Duration.ofMinutes(5), 1, Duration.ZERO, input -> "default", true, 3, true, null);
    }
    public JobResult execute(JobContext context) {
        long deleted = 0;
        for (int batch = 0; batch < 10; batch++) {
            int count = context.transaction(() -> failures.purgeExpired(clock.instant(), 200));
            deleted += count;
            if (count < 200) return JobResult.success("LOGIN_FAILURES_CLEANED", Map.of("deleted", deleted));
        }
        return JobResult.partial("BATCH_LIMIT_REACHED", Map.of("deleted", deleted));
    }
}
