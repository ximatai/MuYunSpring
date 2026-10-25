package net.ximatai.muyun.spring.platform.exchange;

import net.ximatai.muyun.spring.platform.job.JobContext;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RecordExchangeRetentionTest {
    @Test
    void preservesHourlyFixedDelayAndFirstMinuteDelay() {
        var handler = new RecordExchangeRetention(mock(RecordExchangeReceipts.class));
        assertThat(handler.definition().defaultSchedule().interval()).isEqualTo(Duration.ofHours(1));
        assertThat(handler.definition().defaultSchedule().initialDelay()).isEqualTo(Duration.ofMinutes(1));
        assertThat(handler.definition().maximumAttempts()).isEqualTo(1);
    }

    @Test
    void stopsAfterFirstIncompleteBatch() {
        var receipts = mock(RecordExchangeReceipts.class);
        when(receipts.purgeExpired(any(Instant.class))).thenReturn(200, 17);
        var result = new RecordExchangeRetention(receipts).execute(context());
        assertThat(result.code()).isEqualTo("COMPLETE");
        verify(receipts, times(2)).purgeExpired(any(Instant.class));
    }

    @Test
    void batchLimitIsNormalPartialSuccessAndNeverAnEleventhBatch() {
        var receipts = mock(RecordExchangeReceipts.class);
        when(receipts.purgeExpired(any(Instant.class))).thenReturn(200);
        var result = new RecordExchangeRetention(receipts).execute(context());
        assertThat(result.code()).isEqualTo("BATCH_LIMIT_REACHED");
        verify(receipts, times(10)).purgeExpired(any(Instant.class));
    }

    @Test
    void errorsReachThePlatformAndCancellationStopsBeforeNextBatch() {
        var receipts = mock(RecordExchangeReceipts.class);
        when(receipts.purgeExpired(any(Instant.class))).thenThrow(new IllegalStateException("unavailable"));
        assertThatIllegalStateException().isThrownBy(() -> new RecordExchangeRetention(receipts).execute(context()));
        reset(receipts);
        var context = context();
        when(context.cancelled()).thenReturn(true);
        new RecordExchangeRetention(receipts).execute(context);
        verifyNoInteractions(receipts);
    }

    private JobContext context() {
        var context = mock(JobContext.class);
        when(context.transaction(any(Supplier.class))).thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(0).get());
        return context;
    }
}
