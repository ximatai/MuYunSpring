package net.ximatai.muyun.spring.ability.logging;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/** Bounded storage request for removing business-log facts older than one exclusive cutoff. */
public record BusinessLogRetentionRequest(
        Instant occurredBefore,
        Set<BusinessLogEventType> eventTypes,
        int batchSize,
        int maximumBatches
) {
    public BusinessLogRetentionRequest {
        occurredBefore = Objects.requireNonNull(occurredBefore, "occurredBefore must not be null");
        if (eventTypes != null) {
            if (eventTypes.isEmpty() || eventTypes.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("eventTypes must be null or contain at least one event type");
            }
            eventTypes = Set.copyOf(eventTypes);
        }
        if (batchSize < 1 || batchSize > 10_000) {
            throw new IllegalArgumentException("batchSize must be between 1 and 10000");
        }
        if (maximumBatches < 1 || maximumBatches > 1_000) {
            throw new IllegalArgumentException("maximumBatches must be between 1 and 1000");
        }
    }

    /** Creates one retention request applying to every business-log event type. */
    public BusinessLogRetentionRequest(Instant occurredBefore, int batchSize, int maximumBatches) {
        this(occurredBefore, null, batchSize, maximumBatches);
    }
}
