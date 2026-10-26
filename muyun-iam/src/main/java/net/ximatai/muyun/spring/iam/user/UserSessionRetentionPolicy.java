package net.ximatai.muyun.spring.iam.user;

/** Deployment policy for ended session history, independent of job-run retention. */
public record UserSessionRetentionPolicy(int retentionDays, int batchSize, int maxBatches) {
    public UserSessionRetentionPolicy {
        if (retentionDays < 1 || retentionDays > 36500)
            throw new IllegalArgumentException("session retention-days must be between 1 and 36500");
        if (batchSize < 1 || batchSize > 1000)
            throw new IllegalArgumentException("session batch-size must be between 1 and 1000");
        if (maxBatches < 1 || maxBatches > 100)
            throw new IllegalArgumentException("session max-batches must be between 1 and 100");
    }
}
