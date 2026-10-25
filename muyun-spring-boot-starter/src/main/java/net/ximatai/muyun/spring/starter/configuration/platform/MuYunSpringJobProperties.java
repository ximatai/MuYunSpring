package net.ximatai.muyun.spring.starter.configuration.platform;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Deployment limits for the durable job queue; disabling schedules leaves the queue running. */
@ConfigurationProperties("muyun.platform.job")
public class MuYunSpringJobProperties {
    private boolean scheduledEnabled = true;
    private Duration scanInterval = Duration.ofMinutes(1);
    private Duration leaseDuration = Duration.ofMinutes(1);
    private Duration shutdownGrace = Duration.ofSeconds(30);
    private int retentionDays = 90;
    private Map<String, Channel> channels = new LinkedHashMap<>(Map.of("default", new Channel()));

    public boolean isScheduledEnabled() { return scheduledEnabled; }
    public void setScheduledEnabled(boolean value) { scheduledEnabled = value; }
    public Duration getScanInterval() { return scanInterval; }
    public void setScanInterval(Duration value) { scanInterval = value; }
    public Duration getLeaseDuration() { return leaseDuration; }
    public void setLeaseDuration(Duration value) { leaseDuration = value; }
    public Duration getShutdownGrace() { return shutdownGrace; }
    public void setShutdownGrace(Duration value) { shutdownGrace = value; }
    public int getRetentionDays() { return retentionDays; }
    public void setRetentionDays(int value) { retentionDays = value; }
    public Map<String, Channel> getChannels() { return channels; }
    public void setChannels(Map<String, Channel> value) { channels = value; }

    public void validate() {
        duration("scanInterval", scanInterval, Duration.ofSeconds(1), Duration.ofHours(1));
        duration("leaseDuration", leaseDuration, Duration.ofSeconds(30), Duration.ofHours(1));
        duration("shutdownGrace", shutdownGrace, Duration.ZERO, Duration.ofMinutes(5));
        if (retentionDays < 1 || retentionDays > 36_500)
            throw new IllegalArgumentException("retentionDays must be between 1 and 36500");
        if (channels == null || channels.isEmpty() || channels.size() > 32)
            throw new IllegalArgumentException("Between 1 and 32 job channels are required");
        channels.forEach((name, channel) -> {
            if (name == null || !name.matches("[a-z][a-z0-9_-]{0,63}") || channel == null)
                throw new IllegalArgumentException("Invalid job channel");
            if (channel.threads < 1 || channel.threads > 64)
                throw new IllegalArgumentException("Job channel threads must be between 1 and 64");
            duration("claimInterval", channel.claimInterval, Duration.ofMillis(100), Duration.ofMinutes(1));
        });
        if (channels.values().stream().mapToInt(Channel::getThreads).sum() > 256)
            throw new IllegalArgumentException("Total job channel threads must not exceed 256");
    }

    private static void duration(String name, Duration value, Duration minimum, Duration maximum) {
        if (value == null || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0)
            throw new IllegalArgumentException(name + " is outside the supported range");
    }

    public static class Channel {
        private int threads = 2;
        private Duration claimInterval = Duration.ofSeconds(1);

        public int getThreads() { return threads; }
        public void setThreads(int value) { threads = value; }
        public Duration getClaimInterval() { return claimInterval; }
        public void setClaimInterval(Duration value) { claimInterval = value; }
    }
}
