package net.ximatai.muyun.spring.starter.configuration.platform;

import net.ximatai.muyun.spring.platform.logging.BusinessLogRetentionExecutionLimits;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment-owned scheduling and database load limits for persisted business-log retention. */
@ConfigurationProperties("muyun.platform.business-log.retention")
public class MuYunSpringBusinessLogRetentionProperties {
    private boolean scheduledEnabled = true;
    private int batchSize = 1_000;
    private int maximumBatchesPerPolicy = 20;

    public boolean isScheduledEnabled() {
        return scheduledEnabled;
    }

    public void setScheduledEnabled(boolean scheduledEnabled) {
        this.scheduledEnabled = scheduledEnabled;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaximumBatchesPerPolicy() {
        return maximumBatchesPerPolicy;
    }

    public void setMaximumBatchesPerPolicy(int maximumBatchesPerPolicy) {
        this.maximumBatchesPerPolicy = maximumBatchesPerPolicy;
    }

    BusinessLogRetentionExecutionLimits executionLimits() {
        return new BusinessLogRetentionExecutionLimits(batchSize, maximumBatchesPerPolicy);
    }
}
