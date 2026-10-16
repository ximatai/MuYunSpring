package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowTaskActionAvailabilityServiceTest {
    @org.junit.jupiter.api.BeforeEach
    void installWorkflowMutationHost() { WorkflowTestMutationHost.install(); }
    @org.junit.jupiter.api.AfterEach
    void resetWorkflowMutationHost() { WorkflowTestMutationHost.reset(); }

    private final WorkflowTaskDao taskDao = mock(WorkflowTaskDao.class);
    private final WorkflowInstanceDao instanceDao = mock(WorkflowInstanceDao.class);
    private final WorkflowNodeInstanceDao nodeDao = mock(WorkflowNodeInstanceDao.class);
    private final WorkflowRouteInstanceDao routeDao = mock(WorkflowRouteInstanceDao.class);
    private final WorkflowTaskActionAvailabilityService service =
            new WorkflowTaskActionAvailabilityService(taskDao, instanceDao, nodeDao, routeDao,
                    new WorkflowTaskAssignmentPolicyService(), WorkflowUserTitleResolver.NONE);

    @Test
    void shouldListApprovalActionsByNodePolicy() {
        WorkflowTask task = task("task-1", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        WorkflowInstance instance = instance(WorkflowInstanceStatus.RUNNING, null);
        WorkflowNodeInstance node = node();
        node.setRequireRejectReason(true);
        node.setAllowRejectReturnToMe(true);
        node.setAllowRollback(true);
        node.setRequireRollbackReason(true);
        node.setAllowAddSign(true);
        when(taskDao.findById("task-1")).thenReturn(task);
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(nodeDao.findById("node-1")).thenReturn(node);
        when(taskDao.query(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(task));
        when(routeDao.query(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(candidateRoute()));

        List<WorkflowTaskAvailableAction> actions = service.availableActions("task-1", "user-1");

        assertThat(codes(actions)).containsExactly("approve", "reject", "rollback", "addSign", "transfer");
        assertThat(action(actions, "reject").reasonRequired()).isTrue();
        assertThat(action(actions, "reject").rejectReturnToMeSupported()).isTrue();
        assertThat(action(actions, "rollback").reasonRequired()).isTrue();
        assertThat(action(actions, "addSign").reasonRequired()).isTrue();
        assertThat(action(actions, "addSign").targetAssigneeRequired()).isFalse();
        assertThat(action(actions, "transfer").targetAssigneeRequired()).isTrue();
    }

    @Test
    void shouldHideAddSignWhenNodeHasMultipleTodoApprovalTasks() {
        WorkflowTask task = task("task-1", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        WorkflowTask sibling = task("task-2", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        WorkflowNodeInstance node = node();
        node.setAllowAddSign(true);
        when(taskDao.findById("task-1")).thenReturn(task);
        when(instanceDao.findById("instance-1")).thenReturn(instance(WorkflowInstanceStatus.RUNNING, null));
        when(nodeDao.findById("node-1")).thenReturn(node);
        when(taskDao.query(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(task, sibling));

        assertThat(codes(service.availableActions("task-1", "user-1"))).doesNotContain("addSign");
    }

    @Test
    void shouldHideAddSignWhenNodeHasNoOutgoingCandidateRoute() {
        WorkflowTask task = task("task-1", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        WorkflowNodeInstance node = node();
        node.setAllowAddSign(true);
        when(taskDao.findById("task-1")).thenReturn(task);
        when(instanceDao.findById("instance-1")).thenReturn(instance(WorkflowInstanceStatus.RUNNING, null));
        when(nodeDao.findById("node-1")).thenReturn(node);
        when(taskDao.query(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(task));
        when(routeDao.query(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());

        assertThat(codes(service.availableActions("task-1", "user-1"))).doesNotContain("addSign");
    }

    @Test
    void shouldHideDisabledApprovalActions() {
        WorkflowTask task = task("task-1", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        WorkflowNodeInstance node = node();
        node.setAllowReject(false);
        node.setAllowRollback(false);
        when(taskDao.findById("task-1")).thenReturn(task);
        when(instanceDao.findById("instance-1")).thenReturn(instance(WorkflowInstanceStatus.RUNNING, null));
        when(nodeDao.findById("node-1")).thenReturn(node);

        List<WorkflowTaskAvailableAction> actions = service.availableActions("task-1", "user-1");

        assertThat(codes(actions)).containsExactly("approve", "transfer");
    }

    @Test
    void shouldExposeActionsToDelegatedPrincipalWhenAllowed() {
        WorkflowTask task = task("task-1", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        task.setAssignmentKind(WorkflowAssignmentKind.DELEGATED);
        task.setOriginalAssigneeId("principal-1");
        task.setAssigneeId("delegate-1");
        task.setDelegatedFromUserId("principal-1");
        task.setDelegatedToUserId("delegate-1");
        task.setPrincipalCanProcess(true);
        WorkflowNodeInstance node = node();
        node.setNodeTitle("合同审批");
        when(taskDao.findById("task-1")).thenReturn(task);
        when(instanceDao.findById("instance-1")).thenReturn(instance(WorkflowInstanceStatus.RUNNING, null));
        when(nodeDao.findById("node-1")).thenReturn(node);
        when(taskDao.query(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(task));
        when(routeDao.query(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(candidateRoute()));
        WorkflowTaskActionAvailabilityService serviceWithTitles = new WorkflowTaskActionAvailabilityService(
                taskDao, instanceDao, nodeDao, routeDao, null, userIds -> Map.of(
                "principal-1", "原审批人",
                "delegate-1", "代理人"));

        List<WorkflowTaskAvailableAction> actions = serviceWithTitles.availableActions("task-1", "principal-1");
        assertThat(codes(actions)).containsExactly("approve", "reject", "transfer");
        assertThat(action(actions, "approve").nodeTitle()).isEqualTo("合同审批");
        assertThat(action(actions, "approve").assignmentKind()).isEqualTo(WorkflowAssignmentKind.DELEGATED);
        assertThat(action(actions, "approve").originalAssigneeId()).isEqualTo("principal-1");
        assertThat(action(actions, "approve").originalAssigneeTitle()).isEqualTo("原审批人");
        assertThat(action(actions, "approve").delegatedFromUserId()).isEqualTo("principal-1");
        assertThat(action(actions, "approve").delegatedFromUserTitle()).isEqualTo("原审批人");
        assertThat(action(actions, "approve").delegatedToUserId()).isEqualTo("delegate-1");
        assertThat(action(actions, "approve").delegatedToUserTitle()).isEqualTo("代理人");
        assertThat(action(actions, "approve").principalCanProcess()).isTrue();
        assertThat(serviceWithTitles.availableActions("task-1", "other")).isEmpty();
    }

    @Test
    void shouldListBusinessNoticeAndReturnResubmitActions() {
        WorkflowTask business = task("business-1", WorkflowTaskKind.BUSINESS, WorkflowTaskStatus.TODO);
        WorkflowTask notice = task("notice-1", WorkflowTaskKind.NOTICE, WorkflowTaskStatus.TODO);
        WorkflowTask resubmit = task("resubmit-1", WorkflowTaskKind.RESUBMIT, WorkflowTaskStatus.TODO);
        resubmit.setNodeInstanceId(null);
        resubmit.setAssigneeId("starter-1");
        when(taskDao.findById("business-1")).thenReturn(business);
        when(taskDao.findById("notice-1")).thenReturn(notice);
        when(taskDao.findById("resubmit-1")).thenReturn(resubmit);
        when(nodeDao.findById("node-1")).thenReturn(node());
        when(instanceDao.findById("instance-1")).thenReturn(
                instance(WorkflowInstanceStatus.RUNNING, null),
                instance(WorkflowInstanceStatus.RUNNING, null),
                instance(WorkflowInstanceStatus.REJECTED, WorkflowRejectResubmitMode.RETURN_TO_ME));

        assertThat(codes(service.availableActions("business-1", "user-1"))).containsExactly("complete", "transfer");
        assertThat(codes(service.availableActions("notice-1", "user-1"))).containsExactly("notice", "transfer");
        assertThat(codes(service.availableActions("resubmit-1", "starter-1"))).containsExactly("resubmit");
    }

    @Test
    void shouldReturnEmptyWhenTaskIsNotTodoOrOperatorIsNotAssignee() {
        WorkflowTask done = task("done-1", WorkflowTaskKind.BUSINESS, WorkflowTaskStatus.DONE);
        WorkflowTask other = task("other-1", WorkflowTaskKind.BUSINESS, WorkflowTaskStatus.TODO);
        when(taskDao.findById("done-1")).thenReturn(done);
        when(taskDao.findById("other-1")).thenReturn(other);
        when(instanceDao.findById("instance-1")).thenReturn(instance(WorkflowInstanceStatus.RUNNING, null));
        when(nodeDao.findById("node-1")).thenReturn(node());

        assertThat(service.availableActions("done-1", "user-1")).isEmpty();
        assertThat(service.availableActions("other-1", "user-2")).isEmpty();
        verify(instanceDao, org.mockito.Mockito.times(2)).findById("instance-1");
        verify(nodeDao, org.mockito.Mockito.times(2)).findById("node-1");
    }

    @Test
    void shouldRejectBlankTaskIdAndMissingTask() {
        assertThatThrownBy(() -> service.availableActions(" ", "user-1"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("task id");
        assertThatThrownBy(() -> service.availableActions("missing", "user-1"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("workflow task not found");
    }

    private List<String> codes(List<WorkflowTaskAvailableAction> actions) {
        return actions.stream().map(WorkflowTaskAvailableAction::actionCode).toList();
    }

    private WorkflowTaskAvailableAction action(List<WorkflowTaskAvailableAction> actions, String actionCode) {
        return actions.stream()
                .filter(action -> actionCode.equals(action.actionCode()))
                .findFirst()
                .orElseThrow();
    }

    private WorkflowTask task(String id, WorkflowTaskKind kind, WorkflowTaskStatus status) {
        WorkflowTask task = new WorkflowTask();
        task.setId(id);
        task.setInstanceId("instance-1");
        task.setNodeInstanceId("node-1");
        task.setTaskKind(kind);
        task.setTaskStatus(status);
        task.setAssigneeId("user-1");
        return task;
    }

    private WorkflowNodeInstance node() {
        WorkflowNodeInstance node = new WorkflowNodeInstance();
        node.setId("node-1");
        node.setInstanceId("instance-1");
        node.setNodeKey("approve");
        node.setNodeType(WorkflowNodeType.APPROVAL);
        node.setNodeStatus(WorkflowNodeStatus.ACTIVE);
        return node;
    }

    private WorkflowInstance instance(WorkflowInstanceStatus status, WorkflowRejectResubmitMode mode) {
        WorkflowInstance instance = new WorkflowInstance();
        instance.setId("instance-1");
        instance.setInstanceStatus(status);
        instance.setRejectResubmitMode(mode);
        return instance;
    }

    private WorkflowRouteInstance candidateRoute() {
        WorkflowRouteInstance route = new WorkflowRouteInstance();
        route.setId("route-1");
        route.setInstanceId("instance-1");
        route.setSourceNodeKey("approve");
        route.setTargetNodeKey("next");
        route.setRouteStatus(WorkflowRouteStatus.CANDIDATE);
        return route;
    }
}
