package net.ximatai.muyun.spring.iam.federation;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.spring.platform.job.*;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** Platform-wide expiry, including consumed and abandoned login transactions; bounded short batches. */
@Component
@ConditionalOnExternalIdentityFederation
public class ExternalLoginTransactionRetentionJob implements JobHandler {
    public static final String TYPE = "iam.external-login-retention";
    private final IDatabaseOperations<?> database;

    public ExternalLoginTransactionRetentionJob(IDatabaseOperations<?> database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public JobDefinition definition() {
        return new JobDefinition(TYPE, "iam.job.type.externalLoginRetention", JobScope.PLATFORM,
                JobSchedule.fixedDelay(Duration.ofHours(1), Duration.ofMinutes(1)), "default", null, Duration.ofMinutes(5),
                1, Duration.ZERO, input -> "default", true, 3, true, null);
    }
    public JobResult execute(JobContext context) {
        long deleted = 0;
        for (int batch = 0; batch < 10; batch++) {
            int count = context.transaction(() -> database.execute("""
                delete from iam_external_login_transaction where id in
                (select id from iam_external_login_transaction where expires_at < clock_timestamp()
                 order by expires_at, id limit 200 for update skip locked)
                """));
            deleted += count;
            if (count < 200) return JobResult.success("LOGIN_TRANSACTIONS_CLEANED", Map.of("deleted", deleted));
        }
        return JobResult.partial("BATCH_LIMIT_REACHED", Map.of("deleted", deleted));
    }
}
