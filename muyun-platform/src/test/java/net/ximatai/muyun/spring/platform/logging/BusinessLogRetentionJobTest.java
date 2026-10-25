package net.ximatai.muyun.spring.platform.logging;

import net.ximatai.muyun.spring.ability.logging.*;
import net.ximatai.muyun.spring.platform.job.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessLogRetentionJobTest {
    private final BusinessLogRetentionService service = mock(BusinessLogRetentionService.class);
    private final BusinessLogRetentionExecutionLimits limits = new BusinessLogRetentionExecutionLimits(1000, 20);
    private final BusinessLogRetentionJob handler = new BusinessLogRetentionJob(service, limits,
            JobSchedule.fixedDelay(Duration.ofHours(24), Duration.ofMinutes(1)));

    @Test void preservesDefaultsAndExecutionLimits() {
        assertThat(handler.definition().defaultSchedule().interval()).isEqualTo(Duration.ofHours(24));
        assertThat(handler.definition().defaultSchedule().initialDelay()).isEqualTo(Duration.ofMinutes(1));
        assertThat(handler.definition().maximumAttempts()).isEqualTo(1);
        assertThat(handler.definition().channel()).isEqualTo("default");
        when(service.purgeEnabled(limits)).thenReturn(List.of());
        assertThat(handler.execute(mock(JobContext.class)).code()).isEqualTo("COMPLETE");
        verify(service).purgeEnabled(limits);
    }

    @Test void mapsEveryPolicyStatusAndPartialTakesPrecedenceOverSkipped() {
        for (var status : BusinessLogRetentionResult.Status.values()) {
            when(service.purgeEnabled(limits)).thenReturn(List.of(run(BusinessLogEventType.LOGIN, status)));
            var result = handler.execute(mock(JobContext.class));
            assertThat(result.partial()).isEqualTo(status == BusinessLogRetentionResult.Status.BATCH_LIMIT_REACHED);
            assertThat(result.code()).isEqualTo(switch (status) {
                case COMPLETE -> "COMPLETE";
                case ALREADY_RUNNING -> "SKIPPED_ALREADY_RUNNING";
                case BATCH_LIMIT_REACHED -> "BATCH_LIMIT_REACHED";
            });
            assertThat(result.statistics()).containsEntry("LOGIN_deleted", status == BusinessLogRetentionResult.Status.ALREADY_RUNNING ? 0L : 17L)
                    .containsEntry("LOGIN_batches", status == BusinessLogRetentionResult.Status.ALREADY_RUNNING ? 0L : 2L).containsEntry("LOGIN_" + status.name(), 1L);
        }
        when(service.purgeEnabled(limits)).thenReturn(List.of(
                run(BusinessLogEventType.LOGIN, BusinessLogRetentionResult.Status.ALREADY_RUNNING),
                run(BusinessLogEventType.ACTION, BusinessLogRetentionResult.Status.BATCH_LIMIT_REACHED)));
        var result = handler.execute(mock(JobContext.class));
        assertThat(result.code()).isEqualTo("BATCH_LIMIT_REACHED");
        assertThat(result.statistics()).containsEntry("policies", 2L).containsEntry("skipped", 1L)
                .containsEntry("ACTION_BATCH_LIMIT_REACHED", 1L);
    }

    @Test void storageFailuresReachThePlatform() {
        when(service.purgeEnabled(limits)).thenThrow(new IllegalStateException("unavailable"));
        assertThatIllegalStateException().isThrownBy(() -> handler.execute(mock(JobContext.class)));
    }

    private BusinessLogRetentionRunResult run(BusinessLogEventType type, BusinessLogRetentionResult.Status status) {
        return new BusinessLogRetentionRunResult(type, 30, new BusinessLogRetentionResult(Instant.now(),
                status == BusinessLogRetentionResult.Status.ALREADY_RUNNING ? 0 : 17,
                status == BusinessLogRetentionResult.Status.ALREADY_RUNNING ? 0 : 2, status));
    }
}
