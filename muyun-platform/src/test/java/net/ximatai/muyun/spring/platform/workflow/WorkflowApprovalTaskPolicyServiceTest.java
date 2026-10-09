package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowApprovalTaskPolicyServiceTest {
    @org.junit.jupiter.api.BeforeEach
    void installWorkflowMutationHost() { WorkflowTestMutationHost.install(); }
    @org.junit.jupiter.api.AfterEach
    void resetWorkflowMutationHost() { WorkflowTestMutationHost.reset(); }

    private final WorkflowApprovalTaskPolicyService service = new WorkflowApprovalTaskPolicyService();

    @Test
    void shouldPassAnyApprovalWhenOneTaskDone() {
        assertThat(service.isNodePassed(WorkflowApprovalMode.ANY, null, List.of(
                approvalTask(WorkflowTaskStatus.TODO),
                approvalTask(WorkflowTaskStatus.DONE)
        ))).isTrue();
    }

    @Test
    void shouldPassAllApprovalOnlyWhenAllApprovalTasksDone() {
        assertThat(service.isNodePassed(WorkflowApprovalMode.ALL, null, List.of(
                approvalTask(WorkflowTaskStatus.DONE),
                approvalTask(WorkflowTaskStatus.TODO)
        ))).isFalse();
        assertThat(service.isNodePassed(WorkflowApprovalMode.ALL, null, List.of(
                approvalTask(WorkflowTaskStatus.DONE),
                approvalTask(WorkflowTaskStatus.DONE)
        ))).isTrue();
    }

    @Test
    void shouldIgnoreTransferredAndInvalidatedApprovalTasks() {
        assertThat(service.isNodePassed(WorkflowApprovalMode.ALL, null, List.of(
                approvalTask(WorkflowTaskStatus.TRANSFERRED),
                approvalTask(WorkflowTaskStatus.INVALIDATED),
                approvalTask(WorkflowTaskStatus.ROLLED_BACK),
                approvalTask(WorkflowTaskStatus.DONE)
        ))).isTrue();
    }

    @Test
    void shouldPassRatioApprovalByPercent() {
        assertThat(service.isNodePassed(WorkflowApprovalMode.RATIO, 60, List.of(
                approvalTask(WorkflowTaskStatus.DONE),
                approvalTask(WorkflowTaskStatus.DONE),
                approvalTask(WorkflowTaskStatus.TODO)
        ))).isTrue();
        assertThat(service.isNodePassed(WorkflowApprovalMode.RATIO, 80, List.of(
                approvalTask(WorkflowTaskStatus.DONE),
                approvalTask(WorkflowTaskStatus.DONE),
                approvalTask(WorkflowTaskStatus.TODO)
        ))).isFalse();
    }

    @Test
    void shouldTreatNoticeAsPassedWithoutApprovalTasks() {
        assertThat(service.isNodePassed(WorkflowApprovalMode.NOTICE, null, List.of())).isTrue();
    }

    @Test
    void shouldRejectInvalidRatio() {
        assertThatThrownBy(() -> service.isNodePassed(WorkflowApprovalMode.RATIO, 0, List.of(
                approvalTask(WorkflowTaskStatus.DONE)
        ))).isInstanceOf(PlatformException.class);
    }

    private WorkflowTask approvalTask(WorkflowTaskStatus status) {
        WorkflowTask task = new WorkflowTask();
        task.setTaskKind(WorkflowTaskKind.APPROVAL);
        task.setTaskStatus(status);
        return task;
    }
}
