package net.ximatai.muyun.spring.platform.attachment;

import net.ximatai.muyun.spring.platform.job.JobContext;
import net.ximatai.muyun.spring.platform.job.JobFailure;
import net.ximatai.muyun.spring.platform.job.JobScope;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FileReferenceUploadRetentionJobTest {
    @Test void usesThePlatformHourlyScheduleWithOneMinuteInitialDelay() {
        var definition = new FileReferenceUploadRetentionJob(mock(FileReferenceUploadService.class)).definition();
        assertThat(definition.scope()).isEqualTo(JobScope.PLATFORM);
        assertThat(definition.defaultSchedule().interval()).isEqualTo(Duration.ofHours(1));
        assertThat(definition.defaultSchedule().initialDelay()).isEqualTo(Duration.ofMinutes(1));
        assertThat(definition.maximumAttempts()).isEqualTo(1);
    }

    @Test void stopsAfterAShortBatchAndReportsTheRemovedTotal() {
        var uploads = mock(FileReferenceUploadService.class);
        when(uploads.purgeExpiredBatch()).thenReturn(200, 17);
        var context = context();
        var result = new FileReferenceUploadRetentionJob(uploads).execute(context);
        assertThat(result.code()).isEqualTo("COMPLETE");
        assertThat(result.partial()).isFalse();
        assertThat(result.statistics()).containsEntry("removed", 217L);
        verify(uploads, times(2)).purgeExpiredBatch();
        verify(context, times(2)).transaction(any(Supplier.class));
        verify(context, times(2)).checkCancelled();
    }

    @Test void limitsEachRunToTenBatchesAndReportsPartialCompletion() {
        var uploads = mock(FileReferenceUploadService.class);
        when(uploads.purgeExpiredBatch()).thenReturn(200);
        var context = context();
        var result = new FileReferenceUploadRetentionJob(uploads).execute(context);
        assertThat(result.code()).isEqualTo("BATCH_LIMIT_REACHED");
        assertThat(result.partial()).isTrue();
        assertThat(result.statistics()).containsEntry("removed", 2000L);
        verify(uploads, times(10)).purgeExpiredBatch();
        verify(context, times(10)).transaction(any(Supplier.class));
    }

    @Test void cancellationStopsBeforeTheNextBatchAndStorageFailuresReachThePlatform() {
        var uploads = mock(FileReferenceUploadService.class);
        when(uploads.purgeExpiredBatch()).thenReturn(200);
        var context = context();
        when(context.cancelled()).thenReturn(false, true);
        assertThatThrownBy(() -> new FileReferenceUploadRetentionJob(uploads).execute(context))
                .isInstanceOf(JobFailure.class).hasMessageContaining("CANCELLED");
        verify(uploads).purgeExpiredBatch();
        verify(context).transaction(any(Supplier.class));
        reset(uploads);
        when(uploads.purgeExpiredBatch()).thenThrow(new IllegalStateException("storage unavailable"));
        assertThatIllegalStateException().isThrownBy(() -> new FileReferenceUploadRetentionJob(uploads).execute(context()))
                .withMessage("storage unavailable");
    }

    @SuppressWarnings("unchecked")
    private JobContext context() {
        var context = mock(JobContext.class);
        doCallRealMethod().when(context).checkCancelled();
        when(context.transaction(any(Supplier.class))).thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(0).get());
        return context;
    }
}
