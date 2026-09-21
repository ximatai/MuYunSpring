package net.ximatai.muyun.spring.ability.logging;

import java.time.Instant;
import java.util.Objects;

/** Outcome of one bounded business-log retention run. */
public record BusinessLogRetentionResult(
        Instant occurredBefore,
        long deletedCount,
        int executedBatches,
        Status status
) {
    public BusinessLogRetentionResult {
        occurredBefore = Objects.requireNonNull(occurredBefore, "occurredBefore must not be null");
        status = Objects.requireNonNull(status, "status must not be null");
        if (deletedCount < 0 || executedBatches < 0) {
            throw new IllegalArgumentException("retention result counts must not be negative");
        }
        if (status == Status.ALREADY_RUNNING && (deletedCount != 0 || executedBatches != 0)) {
            throw new IllegalArgumentException("an already-running result must not report executed work");
        }
    }

    public enum Status {
        COMPLETE,
        BATCH_LIMIT_REACHED,
        ALREADY_RUNNING
    }
}
