package net.ximatai.muyun.spring.platform.logging;

/** Deployment-owned database load limits for one retention-policy execution. */
public record BusinessLogRetentionExecutionLimits(int batchSize, int maximumBatchesPerPolicy) {
    public BusinessLogRetentionExecutionLimits {
        if (batchSize < 1 || batchSize > 10_000) {
            throw new IllegalArgumentException("batchSize must be between 1 and 10000");
        }
        if (maximumBatchesPerPolicy < 1 || maximumBatchesPerPolicy > 1_000) {
            throw new IllegalArgumentException("maximumBatchesPerPolicy must be between 1 and 1000");
        }
    }
}
