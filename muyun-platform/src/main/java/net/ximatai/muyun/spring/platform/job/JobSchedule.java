package net.ximatai.muyun.spring.platform.job;

import org.springframework.scheduling.support.CronExpression;
import java.time.*;
import java.util.Objects;

/** Minute precision calendar schedules and completion-based fixed delays. */
public record JobSchedule(String kind, String expression, String zoneId, Duration interval, Duration initialDelay) {
    public JobSchedule {
        if ("CRON".equals(kind)) {
            if (expression == null || !expression.trim().startsWith("0 "))
                throw new IllegalArgumentException("Job cron seconds must be zero");
            CronExpression.parse(expression);
            zoneId = zoneId == null ? net.ximatai.muyun.spring.ability.PlatformAbilityRuntime.timeService()
                    .resolveZoneId(null).getId() : ZoneId.of(zoneId).getId();
        } else if ("FIXED_DELAY".equals(kind)) {
            Objects.requireNonNull(interval, "interval"); Objects.requireNonNull(initialDelay, "initialDelay");
            if (interval.compareTo(Duration.ofMinutes(1)) < 0 || initialDelay.isNegative())
                throw new IllegalArgumentException("Invalid job fixed delay");
        } else throw new IllegalArgumentException("Unknown job schedule kind");
    }
    public static JobSchedule cron(String expression, String zoneId) {
        return new JobSchedule("CRON", expression, zoneId, null, null);
    }
    public static JobSchedule fixedDelay(Duration interval, Duration initialDelay) {
        return new JobSchedule("FIXED_DELAY", null, null, interval, initialDelay);
    }
    public Instant first(Instant now) { return "FIXED_DELAY".equals(kind) ? now.plus(initialDelay) : next(now); }
    public Instant next(Instant now) {
        if ("FIXED_DELAY".equals(kind)) return now.plus(interval);
        ZoneId zone = ZoneId.of(zoneId);
        var cron = CronExpression.parse(expression);
        // Local calendar iteration omits nonexistent times and selects only the earlier overlap offset.
        LocalDateTime cursor = LocalDateTime.ofInstant(now, zone);
        for (int i = 0; i < 10000; i++) {
            cursor = cron.next(cursor);
            if (cursor == null) throw new IllegalArgumentException("Job cron has no next occurrence");
            var offsets = zone.getRules().getValidOffsets(cursor);
            if (!offsets.isEmpty()) {
                Instant candidate = cursor.toInstant(offsets.getFirst());
                if (candidate.isAfter(now)) return candidate;
            }
        }
        throw new IllegalArgumentException("Job cron has no valid occurrence");
    }
    public static JobSchedule of(JobPlan plan) {
        return new JobSchedule(plan.getScheduleKind(), plan.getExpression(), plan.getZoneId(),
                plan.getIntervalMillis() == null ? null : Duration.ofMillis(plan.getIntervalMillis()),
                plan.getInitialDelayMillis() == null ? null : Duration.ofMillis(plan.getInitialDelayMillis()));
    }
    public void apply(JobPlan plan) {
        plan.setScheduleKind(kind); plan.setExpression(expression); plan.setZoneId(zoneId);
        plan.setIntervalMillis(interval == null ? null : interval.toMillis());
        plan.setInitialDelayMillis(initialDelay == null ? null : initialDelay.toMillis());
    }
}
