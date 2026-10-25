package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.platform.job.JobContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class WorkflowNodeOvertimeJobTest {
    @Test void preservesDefaultsAndSummarizesTheExistingScanInAGuardedTransaction() {
        var service = mock(WorkflowNodeOvertimeScanService.class);
        when(service.scanBatch(any(), isNull())).thenReturn(new WorkflowNodeOvertimeScanService.Batch(
                new WorkflowNodeOvertimeScanResult(200, 2, 3), "cursor"));
        when(service.scanBatch(any(), eq("cursor"))).thenReturn(new WorkflowNodeOvertimeScanService.Batch(
                new WorkflowNodeOvertimeScanResult(7, 1, 2), null));
        var handler = new WorkflowNodeOvertimeJob(service, 300000, 60000);
        assertThat(handler.definition().defaultSchedule().interval()).isEqualTo(Duration.ofMinutes(5));
        assertThat(handler.definition().defaultSchedule().initialDelay()).isEqualTo(Duration.ofMinutes(1));
        var context = mock(JobContext.class);
        when(context.transaction(any(Supplier.class))).thenAnswer(call -> ((Supplier<?>) call.getArgument(0)).get());
        var result = handler.execute(context);
        assertThat(result.code()).isEqualTo("COMPLETE");
        assertThat(result.partial()).isFalse();
        assertThat(result.statistics()).isEqualTo(Map.of("scanned", 207L, "warned", 3L, "overdue", 5L, "updated", 8L));
        verify(context, times(2)).transaction(any(Supplier.class));
        var times = org.mockito.ArgumentCaptor.forClass(java.time.Instant.class);
        verify(service).scanBatch(times.capture(), isNull());
        verify(service).scanBatch(eq(times.getValue()), eq("cursor"));
    }

    @Test void bindsLegacyMillisecondProperties() {
        new ApplicationContextRunner().withBean(WorkflowNodeOvertimeScanService.class,
                () -> mock(WorkflowNodeOvertimeScanService.class)).withUserConfiguration(WorkflowNodeOvertimeJob.class)
                .withPropertyValues("muyun.workflow.overtime-scan-delay-ms=120000",
                        "muyun.workflow.overtime-scan-initial-delay-ms=0").run(context -> {
                    assertThat(context).hasSingleBean(WorkflowNodeOvertimeJob.class);
                    var schedule = context.getBean(WorkflowNodeOvertimeJob.class).definition().defaultSchedule();
                    assertThat(schedule.interval()).isEqualTo(Duration.ofMinutes(2));
                    assertThat(schedule.initialDelay()).isEqualTo(Duration.ZERO);
                });
    }
}
