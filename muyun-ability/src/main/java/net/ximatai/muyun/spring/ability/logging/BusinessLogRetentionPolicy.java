package net.ximatai.muyun.spring.ability.logging;

import java.time.Instant;
import java.util.Objects;

/** Runtime-managed retention policy for one business-log event type. */
public record BusinessLogRetentionPolicy(
        BusinessLogEventType eventType,
        boolean automaticCleanupEnabled,
        int retentionDays,
        long version,
        Instant updatedAt,
        String updatedBy
) {
    public static final int DEFAULT_RETENTION_DAYS = 180;
    public static final int MAXIMUM_RETENTION_DAYS = 36_500;

    public BusinessLogRetentionPolicy {
        eventType = Objects.requireNonNull(eventType, "eventType must not be null");
        if (retentionDays < 1 || retentionDays > MAXIMUM_RETENTION_DAYS) {
            throw new IllegalArgumentException("retentionDays must be between 1 and " + MAXIMUM_RETENTION_DAYS);
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        updatedBy = BusinessLogContext.optional(updatedBy, "updatedBy", 128);
    }

    public static BusinessLogRetentionPolicy defaultDisabled(BusinessLogEventType eventType) {
        return new BusinessLogRetentionPolicy(eventType, false, DEFAULT_RETENTION_DAYS, 0, null, null);
    }
}
