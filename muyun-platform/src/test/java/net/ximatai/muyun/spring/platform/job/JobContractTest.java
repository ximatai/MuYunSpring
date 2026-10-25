package net.ximatai.muyun.spring.platform.job;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class JobContractTest {
    @Test void calendarSchedulesSkipGapsAndDoNotRepeatOverlap() {
        var spring = JobSchedule.cron("0 30 2 * * *", "America/New_York");
        assertThat(spring.next(Instant.parse("2026-03-08T06:00:00Z")))
                .isEqualTo(Instant.parse("2026-03-09T06:30:00Z"));
        var fall = JobSchedule.cron("0 30 1 * * *", "America/New_York");
        Instant first = fall.next(Instant.parse("2026-11-01T04:00:00Z"));
        assertThat(first).isEqualTo(Instant.parse("2026-11-01T05:30:00Z"));
        assertThat(fall.next(first)).isEqualTo(Instant.parse("2026-11-02T06:30:00Z"));
        assertThat(fall.next(Instant.parse("2026-11-01T06:00:00Z")))
                .isEqualTo(Instant.parse("2026-11-02T06:30:00Z"));
    }
    @Test void fixedDelayUsesInitialDelayAndCompletionWhileCronCollapsesMissedTicks() {
        var fixed = JobSchedule.fixedDelay(Duration.ofHours(1), Duration.ofMinutes(1));
        Instant now = Instant.parse("2026-10-25T00:00:00Z");
        assertThat(fixed.first(now)).isEqualTo(now.plusSeconds(60));
        assertThat(fixed.next(now.plusSeconds(900))).isEqualTo(now.plusSeconds(4500));
        var cron = JobSchedule.cron("0 * * * * *", "Asia/Shanghai");
        assertThat(cron.next(now.plusSeconds(333))).isEqualTo(now.plusSeconds(360));
        assertThatThrownBy(() -> JobSchedule.cron("* * * * * *", "UTC")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JobSchedule.fixedDelay(Duration.ofSeconds(1), Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void effectiveKeysIncludeTypeScopeTenantAndUnambiguousTopic() {
        String a = JobKeys.effective("test.job", JobScope.TENANT, "a", "record:v1");
        assertThat(a).isNotEqualTo(JobKeys.effective("test.job", JobScope.TENANT, "b", "record:v1"));
        assertThat(a).isNotEqualTo(JobKeys.effective("test.other", JobScope.TENANT, "a", "record:v1"));
        assertThat(a).isNotEqualTo(JobKeys.effective("test.job", JobScope.TENANT, "a", "record:v2"));
        assertThat(JobKeys.effective("test.job", JobScope.TENANT, "a:b", "c"))
                .isNotEqualTo(JobKeys.effective("test.job", JobScope.TENANT, "a", "b:c"));
        assertThatThrownBy(() -> JobKeys.effective("test.job", JobScope.PLATFORM, "a", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JobKeys.effective("test.job", JobScope.TENANT, null, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @SuppressWarnings("unchecked")
    @Test void inputIsDeepFrozenAndBounded() {
        var list = new ArrayList<>(List.of("v1"));
        var input = new HashMap<String, Object>(); input.put("nested", Map.of("values", list));
        String frozen = JobService.encodeParameters(input); list.set(0, "v2");
        var copy = JobService.parameters(frozen);
        var nested = (Map<String, Object>) copy.get("nested");
        assertThat((List<String>) nested.get("values")).containsExactly("v1");
        assertThatThrownBy(() -> copy.put("new", "value")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((List<String>) nested.get("values")).add("v3")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> JobService.encodeParameters(Map.of("large", "x".repeat(16384))))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void retryPoliciesAndNormalPartialResultsDoNotBecomeFailures() {
        var type = definition(true);
        assertThat(type.backoff(1)).isEqualTo(Duration.ofSeconds(10));
        assertThat(type.backoff(2)).isEqualTo(Duration.ofSeconds(20));
        assertThat(type.backoff(3)).isEqualTo(Duration.ofSeconds(40));
        assertThat(JobResult.partial("BATCH_LIMIT_REACHED", Map.of("removed", 2000L)).partial()).isTrue();
        assertThat(JobService.shouldNotify(type, "PARTIAL", 0)).isFalse();
        assertThat(JobService.shouldNotify(type, "FAILED", 1)).isTrue();
        assertThat(JobService.shouldNotify(type, "RETRY", 2)).isFalse();
        assertThat(JobService.shouldNotify(type, "RETRY", 3)).isTrue();
        assertThat(JobService.shouldNotify(definition(false), "FAILED", 10)).isFalse();
        assertThatThrownBy(() -> new JobFailure("external secret", true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JobRegistry(List.of(handler(type), handler(type)))).isInstanceOf(IllegalArgumentException.class);
    }
    private JobDefinition definition(boolean notify) {
        return new JobDefinition("test.job", "test.job.name", JobScope.PLATFORM, null, "default", null,
                Duration.ofMinutes(10), 3, Duration.ofSeconds(10), input -> "key", notify, 3, true, null);
    }
    private JobHandler handler(JobDefinition type) {
        return new JobHandler() {
            public JobDefinition definition() { return type; }
            public JobResult execute(JobContext context) { return JobResult.success("COMPLETE", Map.of()); }
        };
    }
}
