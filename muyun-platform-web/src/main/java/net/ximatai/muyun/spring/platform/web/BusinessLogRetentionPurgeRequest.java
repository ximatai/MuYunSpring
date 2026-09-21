package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.logging.BusinessLogRetentionPolicy;

/** One-off cleanup cutoff; it deliberately does not mutate the scheduled retention policy. */
public record BusinessLogRetentionPurgeRequest(int retentionDays) {
    public BusinessLogRetentionPurgeRequest {
        if (retentionDays < 1 || retentionDays > BusinessLogRetentionPolicy.MAXIMUM_RETENTION_DAYS) {
            throw new IllegalArgumentException("retentionDays must be between 1 and "
                    + BusinessLogRetentionPolicy.MAXIMUM_RETENTION_DAYS);
        }
    }
}
