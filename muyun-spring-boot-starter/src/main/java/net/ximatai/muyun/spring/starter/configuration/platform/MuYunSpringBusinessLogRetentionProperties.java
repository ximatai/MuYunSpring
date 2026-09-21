package net.ximatai.muyun.spring.starter.configuration.platform;

import net.ximatai.muyun.spring.platform.logging.BusinessLogRetentionExecutionLimits;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Deployment-owned scheduling and database load limits for persisted business-log retention. */
@ConfigurationProperties("muyun.platform.business-log.retention")
public class MuYunSpringBusinessLogRetentionProperties {
    private boolean scheduledEnabled = true;
    private Duration scanDelay = Duration.ofHours(24);
    private Duration initialDelay = Duration.ofMinutes(1);
    private int batchSize = 1_000;
    private int maximumBatchesPerPolicy = 20;

    public boolean isScheduledEnabled() {
        return scheduledEnabled;
    }

    public void setScheduledEnabled(boolean scheduledEnabled) {
        this.scheduledEnabled = scheduledEnabled;
    }

    public Duration getScanDelay() {
        return scanDelay;
    }

    public void setScanDelay(Duration scanDelay) {
        this.scanDelay = scanDelay;
    }

    public Duration getInitialDelay() {
        return initialDelay;
    }

    public void setInitialDelay(Duration initialDelay) {
        this.initialDelay = initialDelay;
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

    void validateSchedule() {
        if (scanDelay == null || scanDelay.compareTo(Duration.ofMinutes(1)) < 0) {
            throw new IllegalArgumentException("scanDelay must be at least one minute");
        }
        if (initialDelay == null || initialDelay.isNegative()) {
            throw new IllegalArgumentException("initialDelay must not be negative");
        }
    }
}
