package net.ximatai.muyun.spring.platform.workflow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowTaskAssignmentPolicyServiceTest {
    @org.junit.jupiter.api.BeforeEach
    void installWorkflowMutationHost() { WorkflowTestMutationHost.install(); }
    @org.junit.jupiter.api.AfterEach
    void resetWorkflowMutationHost() { WorkflowTestMutationHost.reset(); }

    private final WorkflowTaskAssignmentPolicyService service = new WorkflowTaskAssignmentPolicyService();

    @Test
    void shouldAllowDelegateAndOptionalPrincipalToProcessDelegatedTask() {
        WorkflowTask task = delegatedTask(true);

        assertThat(service.canProcess(task, "delegate-1")).isTrue();
        assertThat(service.canProcess(task, "principal-1")).isTrue();
        assertThat(service.canProcess(task, "other")).isFalse();
    }

    @Test
    void shouldRejectPrincipalWhenPolicyDisallowsOrTaskTransferred() {
        WorkflowTask disallowed = delegatedTask(false);
        WorkflowTask transferred = delegatedTask(true);
        transferred.setAssignmentKind(WorkflowAssignmentKind.TRANSFERRED);
        transferred.setTransferredFromUserId("delegate-1");
        transferred.setAssigneeId("transfer-target");

        assertThat(service.canProcess(disallowed, "principal-1")).isFalse();
        assertThat(service.canProcess(transferred, "principal-1")).isFalse();
        assertThat(service.canProcess(transferred, "transfer-target")).isTrue();
    }

    private WorkflowTask delegatedTask(boolean principalCanProcess) {
        WorkflowTask task = new WorkflowTask();
        task.setId("task-1");
        task.setTaskStatus(WorkflowTaskStatus.TODO);
        task.setAssignmentKind(WorkflowAssignmentKind.DELEGATED);
        task.setOriginalAssigneeId("principal-1");
        task.setAssigneeId("delegate-1");
        task.setDelegatedFromUserId("principal-1");
        task.setDelegatedToUserId("delegate-1");
        task.setPrincipalCanProcess(principalCanProcess);
        return task;
    }
}
