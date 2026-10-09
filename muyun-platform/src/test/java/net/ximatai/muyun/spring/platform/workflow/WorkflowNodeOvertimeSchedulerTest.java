package net.ximatai.muyun.spring.platform.workflow;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WorkflowNodeOvertimeSchedulerTest {
    @org.junit.jupiter.api.BeforeEach
    void installWorkflowMutationHost() { WorkflowTestMutationHost.install(); }
    @org.junit.jupiter.api.AfterEach
    void resetWorkflowMutationHost() { WorkflowTestMutationHost.reset(); }

    private final WorkflowNodeOvertimeScanService scanService = mock(WorkflowNodeOvertimeScanService.class);
    private final WorkflowNodeOvertimeScheduler scheduler = new WorkflowNodeOvertimeScheduler(scanService);

    @Test
    void shouldScanWithRuntimeNowWhenScheduled() {
        scheduler.scanScheduled();

        verify(scanService).scan(null);
    }
}
