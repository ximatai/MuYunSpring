package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.logging.BusinessLogRetentionPolicy;

/** Administrator mutation for one event-type retention policy. */
public record BusinessLogRetentionPolicyRequest(boolean automaticCleanupEnabled, int retentionDays, long version) {
    public BusinessLogRetentionPolicyRequest {
        if (retentionDays < 1 || retentionDays > BusinessLogRetentionPolicy.MAXIMUM_RETENTION_DAYS) {
            throw new IllegalArgumentException("retentionDays must be between 1 and "
                    + BusinessLogRetentionPolicy.MAXIMUM_RETENTION_DAYS);
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
    }
}
