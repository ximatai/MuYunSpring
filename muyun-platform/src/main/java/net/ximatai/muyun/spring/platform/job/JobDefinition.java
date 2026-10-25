package net.ximatai.muyun.spring.platform.job;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

public record JobDefinition(String code, String nameKey, JobScope scope, JobSchedule defaultSchedule,
        String channel, Duration leaseDuration, Duration maximumRuntime, int maximumAttempts,
        Duration retryBackoff, Function<Map<String, Object>, String> topicKey,
        boolean failureNotification, int failureThreshold, boolean manualRetry, Integer retentionDays) {
    public JobDefinition {
        if (code == null || !code.matches("[a-z][a-z0-9_.-]{0,127}") || nameKey == null || nameKey.isBlank()
                || channel == null || !channel.matches("[a-z][a-z0-9_-]{0,63}"))
            throw new IllegalArgumentException("Invalid job type declaration");
        Objects.requireNonNull(scope); Objects.requireNonNull(maximumRuntime); Objects.requireNonNull(retryBackoff);
        Objects.requireNonNull(topicKey);
        if (maximumRuntime.isNegative() || maximumRuntime.isZero() || maximumAttempts < 1 || maximumAttempts > 100
                || retryBackoff.isNegative() || failureThreshold < 1
                || leaseDuration != null && (leaseDuration.compareTo(Duration.ofSeconds(30)) < 0 || leaseDuration.compareTo(Duration.ofHours(1)) > 0)
                || retentionDays != null && (retentionDays < 1 || retentionDays > 36500)
                || scope == JobScope.TENANT && defaultSchedule != null)
            throw new IllegalArgumentException("Invalid job execution policy");
    }
    public Duration backoff(int attempt) {
        return retryBackoff.multipliedBy(Math.min(1L << Math.min(Math.max(attempt - 1, 0), 20), 1024));
    }
}
