package net.ximatai.muyun.spring.platform.exchange;

import net.ximatai.muyun.spring.platform.job.*;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded physical cleanup of expired file identities; it performs no business reads or model work.
 */
@Component
public class RecordExchangeRetention implements JobHandler {
    private final RecordExchangeReceipts receipts;

    public RecordExchangeRetention(RecordExchangeReceipts receipts) {
        this.receipts = Objects.requireNonNull(receipts);
    }

    @Override
    public JobDefinition definition() {
        return new JobDefinition("platform.exchange-retention", "platform.job.type.exchangeRetention", JobScope.PLATFORM,
                JobSchedule.fixedDelay(Duration.ofHours(1), Duration.ofMinutes(1)), "default", null,
                Duration.ofMinutes(10), 1, Duration.ofSeconds(15), parameters -> "exchange-retention",
                true, 3, true, null);
    }

    @Override
    public JobResult execute(JobContext context) {
        long deleted = 0;
        for (int batch = 1; batch <= 10; batch++) {
            if (context.cancelled()) return JobResult.partial("CANCEL_REQUESTED", Map.of("deleted", deleted));
            int removed = context.transaction(() -> receipts.purgeExpired(Instant.now()));
            deleted += removed;
            context.progress(batch * 10);
            if (removed < 200) return JobResult.success("COMPLETE", Map.of("deleted", deleted, "batches", (long) batch));
        }
        return JobResult.partial("BATCH_LIMIT_REACHED", Map.of("deleted", deleted, "batches", 10L));
    }
}
