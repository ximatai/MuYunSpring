package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowRuntimeProgressionServiceTest {
    @org.junit.jupiter.api.BeforeEach
    void installWorkflowMutationHost() { WorkflowTestMutationHost.install(); }
    @org.junit.jupiter.api.AfterEach
    void resetWorkflowMutationHost() { WorkflowTestMutationHost.reset(); }

    private final WorkflowInstanceDao instanceDao = mock(WorkflowInstanceDao.class);
    private final WorkflowNodeInstanceDao nodeDao = mock(WorkflowNodeInstanceDao.class);
    private final WorkflowRouteInstanceDao routeDao = mock(WorkflowRouteInstanceDao.class);
    private final WorkflowTaskDao taskDao = mock(WorkflowTaskDao.class);
    private final WorkflowEventDao eventDao = mock(WorkflowEventDao.class);
    private final WorkflowRuntimeEventFactory eventFactory = new WorkflowRuntimeEventFactory();
    private final WorkflowApprovalSummaryWriter summaryWriter = mock(WorkflowApprovalSummaryWriter.class);

    @Test
    void shouldAdvanceFromCompletedApprovalNodeToNextTaskNode() {
        WorkflowRuntimeProgressionService service = service(Optional.empty());
        WorkflowInstance instance = instance();
        WorkflowNodeInstance approve = node("node-approve", "approve", WorkflowNodeType.APPROVAL,
                WorkflowNodeStatus.COMPLETED);
        WorkflowNodeInstance taskNode = node("node-task", "businessTask", WorkflowNodeType.TASK,
                WorkflowNodeStatus.WAITING);
        WorkflowRouteInstance route = route("route-1", "toTask", "approve", "businessTask", false);
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(nodeDao.query(any(), any())).thenReturn(List.of(approve, taskNode));
        when(routeDao.query(any(), any())).thenReturn(List.of(route));
        when(instanceDao.updateByIdAndVersion(instance, 5)).thenReturn(1);
        when(nodeDao.updateByIdAndVersion(approve, 2)).thenReturn(1);
        when(nodeDao.updateByIdAndVersion(taskNode, 2)).thenReturn(1);
        when(routeDao.updateByIdAndVersion(route, 4)).thenReturn(1);

        WorkflowProgressionResult result = service.advanceFromNode("instance-1", "approve", "user-1",
                Instant.parse("2026-06-05T03:00:00Z"));

        assertThat(route.getRouteStatus()).isEqualTo(WorkflowRouteStatus.EFFECTIVE);
        assertThat(taskNode.getNodeStatus()).isEqualTo(WorkflowNodeStatus.ACTIVE);
        assertThat(instance.getCurrentNodeKeys()).isEqualTo("businessTask");
        assertThat(result.createdTasks()).hasSize(1)
                .first()
                .satisfies(task -> {
                    assertThat(task.getTaskKind()).isEqualTo(WorkflowTaskKind.BUSINESS);
                    assertThat(task.getAssigneeId()).isEqualTo("user-1");
                    assertThat(task.getCreatedAt()).isEqualTo(Instant.parse("2026-06-05T03:00:00Z"));
                });
        assertThat(result.events()).extracting(WorkflowEvent::getEventType)
                .contains(WorkflowEventType.ROUTE_SELECTED, WorkflowEventType.TASK_CREATED);
        verify(taskDao).insert(result.createdTasks().get(0));
    }

    @Test
    void shouldAdvanceThroughInsertedAddSignRouteAndCreateApprovalTask() {
        WorkflowRuntimeProgressionService service = service(Optional.empty());
        WorkflowInstance instance = instance();
        WorkflowNodeInstance approve = node("node-approve", "approve", WorkflowNodeType.APPROVAL,
                WorkflowNodeStatus.COMPLETED);
        WorkflowNodeInstance addSign = node("node-add", "add-1", WorkflowNodeType.APPROVAL,
                WorkflowNodeStatus.WAITING);
        addSign.setAddedByAddSign(true);
        addSign.setAddSignSourceNodeKey("approve");
        addSign.setParticipantPolicyText("user:add-signer-1");
        WorkflowNodeInstance next = node("node-next", "next", WorkflowNodeType.END, WorkflowNodeStatus.WAITING);
        WorkflowRouteInstance replaced = route("route-old", "old", "approve", "next", true);
        replaced.setRouteStatus(WorkflowRouteStatus.CANCELED);
        WorkflowRouteInstance entry = route("route-entry", "entry-add", "approve", "add-1", true);
        entry.setAddedByAddSign(true);
        entry.setAddSignSourceNodeKey("approve");
        WorkflowRouteInstance exit = route("route-exit", "exit-add", "add-1", "next", false);
        exit.setAddedByAddSign(true);
        exit.setAddSignSourceNodeKey("approve");
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(nodeDao.query(any(), any())).thenReturn(List.of(approve, addSign, next));
        when(routeDao.query(any(), any())).thenReturn(List.of(replaced, entry, exit));
        when(instanceDao.updateByIdAndVersion(instance, 5)).thenReturn(1);
        when(nodeDao.updateByIdAndVersion(approve, 2)).thenReturn(1);
        when(nodeDao.updateByIdAndVersion(addSign, 2)).thenReturn(1);
        when(nodeDao.updateByIdAndVersion(next, 2)).thenReturn(1);
        when(routeDao.updateByIdAndVersion(replaced, 4)).thenReturn(1);
        when(routeDao.updateByIdAndVersion(entry, 4)).thenReturn(1);
        when(routeDao.updateByIdAndVersion(exit, 4)).thenReturn(1);

        WorkflowProgressionResult result = service.advanceFromNode("instance-1", "approve", "user-1",
                Instant.parse("2026-06-05T03:00:00Z"));

        assertThat(replaced.getRouteStatus()).isEqualTo(WorkflowRouteStatus.CANCELED);
        assertThat(entry.getRouteStatus()).isEqualTo(WorkflowRouteStatus.EFFECTIVE);
        assertThat(addSign.getNodeStatus()).isEqualTo(WorkflowNodeStatus.ACTIVE);
        assertThat(next.getNodeStatus()).isEqualTo(WorkflowNodeStatus.WAITING);
        assertThat(instance.getCurrentNodeKeys()).isEqualTo("add-1");
        assertThat(result.selectedRoutes()).containsExactly(entry);
        assertThat(result.createdTasks()).hasSize(1)
                .first()
                .satisfies(task -> {
                    assertThat(task.getTaskKind()).isEqualTo(WorkflowTaskKind.APPROVAL);
                    assertThat(task.getNodeInstanceId()).isEqualTo("node-add");
                    assertThat(task.getAssigneeId()).isEqualTo("add-signer-1");
                    assertThat(task.getAssignmentPolicyText()).isEqualTo("user:add-signer-1");
                });
        verify(taskDao).insert(result.createdTasks().get(0));
    }

    @Test
    void shouldWriteApprovalSummaryWhenApprovalCompletedMilestoneIsReached() {
        WorkflowRuntimeProgressionService service = service(Optional.of(summaryWriter));
        WorkflowInstance instance = instance();
        instance.setApprovalEnabled(true);
        WorkflowNodeInstance approve = node("node-approve", "approve", WorkflowNodeType.APPROVAL,
                WorkflowNodeStatus.COMPLETED);
        WorkflowNodeInstance milestone = node("node-mile", "approvalDone", WorkflowNodeType.MILESTONE,
                WorkflowNodeStatus.WAITING);
        milestone.setMilestoneType(WorkflowMilestoneType.APPROVAL_COMPLETED);
        WorkflowNodeInstance end = node("node-end", "end", WorkflowNodeType.END, WorkflowNodeStatus.WAITING);
        WorkflowRouteInstance firstRoute = route("route-1", "toDone", "approve", "approvalDone", true);
        WorkflowRouteInstance secondRoute = route("route-2", "toEnd", "approvalDone", "end", false);
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(nodeDao.query(any(), any())).thenReturn(List.of(approve, milestone, end));
        when(routeDao.query(any(), any())).thenReturn(List.of(firstRoute, secondRoute));
        when(instanceDao.updateByIdAndVersion(instance, 5)).thenReturn(1);
        when(nodeDao.updateByIdAndVersion(approve, 2)).thenReturn(1);
        when(nodeDao.updateByIdAndVersion(milestone, 2)).thenReturn(1);
        when(nodeDao.updateByIdAndVersion(end, 2)).thenReturn(1);
        when(routeDao.updateByIdAndVersion(firstRoute, 4)).thenReturn(1);
        when(routeDao.updateByIdAndVersion(secondRoute, 4)).thenReturn(1);

        WorkflowProgressionResult result = service.advanceFromNode("instance-1", "approve", "user-1",
                Instant.parse("2026-06-05T03:00:00Z"));

        assertThat(instance.getApprovalStatus()).isEqualTo(WorkflowApprovalStatus.APPROVED);
        assertThat(instance.getApprovalCompletedAt()).isEqualTo(Instant.parse("2026-06-05T03:00:00Z"));
        assertThat(instance.getInstanceStatus()).isEqualTo(WorkflowInstanceStatus.COMPLETED);
        assertThat(result.events()).extracting(WorkflowEvent::getEventType)
                .contains(WorkflowEventType.APPROVAL_COMPLETED, WorkflowEventType.INSTANCE_COMPLETED);
        verify(summaryWriter).writeSubmitted(new WorkflowApprovalSummary(
                "tenant-1", "sales.contract", "record-1", "instance-1", WorkflowApprovalStatus.APPROVED,
                "starter-1", Instant.parse("2026-06-05T01:00:00Z"),
                Instant.parse("2026-06-05T03:00:00Z")
        ));
    }

    @Test
    void shouldDropUnselectedOutgoingRoutesWhenDefaultRouteExists() {
        WorkflowRuntimeProgressionService service = service(Optional.empty());
        WorkflowInstance instance = instance();
        WorkflowNodeInstance branch = node("node-branch", "branch", WorkflowNodeType.BRANCH,
                WorkflowNodeStatus.COMPLETED);
        WorkflowNodeInstance left = node("node-left", "leftTask", WorkflowNodeType.TASK, WorkflowNodeStatus.WAITING);
        WorkflowNodeInstance right = node("node-right", "rightTask", WorkflowNodeType.TASK, WorkflowNodeStatus.WAITING);
        WorkflowRouteInstance leftRoute = route("route-left", "leftRoute", "branch", "leftTask", false);
        WorkflowRouteInstance rightRoute = route("route-right", "rightRoute", "branch", "rightTask", true);
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(nodeDao.query(any(), any())).thenReturn(List.of(branch, left, right));
        when(routeDao.query(any(), any())).thenReturn(List.of(leftRoute, rightRoute));
        when(instanceDao.updateByIdAndVersion(instance, 5)).thenReturn(1);
        when(nodeDao.updateByIdAndVersion(branch, 2)).thenReturn(1);
        when(nodeDao.updateByIdAndVersion(left, 2)).thenReturn(1);
        when(nodeDao.updateByIdAndVersion(right, 2)).thenReturn(1);
        when(routeDao.updateByIdAndVersion(leftRoute, 4)).thenReturn(1);
        when(routeDao.updateByIdAndVersion(rightRoute, 4)).thenReturn(1);

        WorkflowProgressionResult result = service.advanceFromNode("instance-1", "branch", "user-1",
                Instant.parse("2026-06-05T03:00:00Z"));

        assertThat(rightRoute.getRouteStatus()).isEqualTo(WorkflowRouteStatus.EFFECTIVE);
        assertThat(leftRoute.getRouteStatus()).isEqualTo(WorkflowRouteStatus.INEFFECTIVE);
        assertThat(right.getNodeStatus()).isEqualTo(WorkflowNodeStatus.ACTIVE);
        assertThat(left.getNodeStatus()).isEqualTo(WorkflowNodeStatus.WAITING);
        assertThat(result.selectedRoutes()).containsExactly(rightRoute);
        assertThat(result.droppedRoutes()).containsExactly(leftRoute);
        assertThat(result.events()).extracting(WorkflowEvent::getEventType)
                .contains(WorkflowEventType.ROUTE_SELECTED, WorkflowEventType.ROUTE_DROPPED);
    }

    @Test
    void shouldUseSelectedRouteKeyFromCompletedBranchNode() {
        WorkflowRuntimeProgressionService service = service(Optional.empty());
        WorkflowInstance instance = instance();
        WorkflowNodeInstance branch = node("node-branch", "branch", WorkflowNodeType.BRANCH,
                WorkflowNodeStatus.COMPLETED);
        branch.setRouteMode(WorkflowRouteMode.MANUAL);
        branch.setSelectorNodeKey("START");
        instance.setStartedBy("user-1");
        WorkflowNodeInstance left = node("node-left", "leftTask", WorkflowNodeType.TASK, WorkflowNodeStatus.WAITING);
        WorkflowNodeInstance right = node("node-right", "rightTask", WorkflowNodeType.TASK, WorkflowNodeStatus.WAITING);
        WorkflowRouteInstance leftRoute = route("route-left", "leftRoute", "branch", "leftTask", false);
        WorkflowRouteInstance rightRoute = route("route-right", "rightRoute", "branch", "rightTask", true);
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(nodeDao.query(any(), any())).thenReturn(List.of(branch, left, right));
        when(routeDao.query(any(), any())).thenReturn(List.of(leftRoute, rightRoute));
        when(instanceDao.updateByIdAndVersion(instance, 5)).thenReturn(1);
        when(nodeDao.updateByIdAndVersion(branch, 2)).thenReturn(1);
        when(nodeDao.updateByIdAndVersion(left, 2)).thenReturn(1);
        when(nodeDao.updateByIdAndVersion(right, 2)).thenReturn(1);
        when(routeDao.updateByIdAndVersion(leftRoute, 4)).thenReturn(1);
        when(routeDao.updateByIdAndVersion(rightRoute, 4)).thenReturn(1);

        WorkflowProgressionResult result = service.advanceFromNode("instance-1", "branch", "user-1",
                Instant.parse("2026-06-05T03:00:00Z"), "leftRoute", "choose left route");

        assertThat(leftRoute.getRouteStatus()).isEqualTo(WorkflowRouteStatus.EFFECTIVE);
        assertThat(leftRoute.getRouteReason()).isEqualTo(WorkflowRouteReason.MANUAL_SELECTED);
        assertThat(leftRoute.getSelectedReason()).isEqualTo("choose left route");
        assertThat(rightRoute.getRouteStatus()).isEqualTo(WorkflowRouteStatus.INEFFECTIVE);
        assertThat(rightRoute.getRouteReason()).isEqualTo(WorkflowRouteReason.MANUAL_UNSELECTED);
        assertThat(rightRoute.getSelectedReason()).isNull();
        assertThat(left.getNodeStatus()).isEqualTo(WorkflowNodeStatus.ACTIVE);
        assertThat(right.getNodeStatus()).isEqualTo(WorkflowNodeStatus.WAITING);
        assertThat(result.selectedRoutes()).containsExactly(leftRoute);
        assertThat(result.droppedRoutes()).containsExactly(rightRoute);
    }

    @Test
    void shouldPassSelectedRouteKeyToReachedBranchDuringActivation() {
        WorkflowRuntimeProgressionService service = service(Optional.empty());
        WorkflowInstance instance = instance();
        WorkflowNodeInstance approve = node("node-approve", "approve", WorkflowNodeType.APPROVAL,
                WorkflowNodeStatus.COMPLETED);
        WorkflowNodeInstance branch = manualBranch("node-branch", "branch", "approve", false);
        WorkflowNodeInstance left = node("node-left", "leftTask", WorkflowNodeType.TASK, WorkflowNodeStatus.WAITING);
        WorkflowNodeInstance right = node("node-right", "rightTask", WorkflowNodeType.TASK, WorkflowNodeStatus.WAITING);
        WorkflowRouteInstance entry = route("route-entry", "toBranch", "approve", "branch", true);
        WorkflowRouteInstance leftRoute = route("route-left", "leftRoute", "branch", "leftTask", false);
        WorkflowRouteInstance rightRoute = route("route-right", "rightRoute", "branch", "rightTask", true);
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(taskDao.query(any(), any())).thenReturn(List.of(task("completed-approval", approve, "user-1")));
        when(nodeDao.query(any(), any())).thenReturn(List.of(approve, branch, left, right));
        when(routeDao.query(any(), any())).thenReturn(List.of(entry, leftRoute, rightRoute));
        when(instanceDao.updateByIdAndVersion(instance, 5)).thenReturn(1);
        when(nodeDao.updateByIdAndVersion(approve, 2)).thenReturn(1);
        when(nodeDao.updateByIdAndVersion(branch, 2)).thenReturn(1);
        when(nodeDao.updateByIdAndVersion(left, 2)).thenReturn(1);
        when(nodeDao.updateByIdAndVersion(right, 2)).thenReturn(1);
        when(routeDao.updateByIdAndVersion(entry, 4)).thenReturn(1);
        when(routeDao.updateByIdAndVersion(leftRoute, 4)).thenReturn(1);
        when(routeDao.updateByIdAndVersion(rightRoute, 4)).thenReturn(1);

        WorkflowProgressionResult result = service.advanceFromNode("instance-1", "approve", "user-1",
                Instant.parse("2026-06-05T03:00:00Z"), "leftRoute", "choose reachable left");

        assertThat(entry.getRouteStatus()).isEqualTo(WorkflowRouteStatus.EFFECTIVE);
        assertThat(entry.getRouteReason()).isEqualTo(WorkflowRouteReason.DEFAULT_SELECTED);
        assertThat(entry.getSelectedReason()).isNull();
        assertThat(leftRoute.getRouteStatus()).isEqualTo(WorkflowRouteStatus.EFFECTIVE);
        assertThat(leftRoute.getRouteReason()).isEqualTo(WorkflowRouteReason.MANUAL_SELECTED);
        assertThat(leftRoute.getSelectedReason()).isEqualTo("choose reachable left");
        assertThat(rightRoute.getRouteStatus()).isEqualTo(WorkflowRouteStatus.INEFFECTIVE);
        assertThat(rightRoute.getRouteReason()).isEqualTo(WorkflowRouteReason.MANUAL_UNSELECTED);
        assertThat(rightRoute.getSelectedReason()).isNull();
        assertThat(left.getNodeStatus()).isEqualTo(WorkflowNodeStatus.ACTIVE);
        assertThat(right.getNodeStatus()).isEqualTo(WorkflowNodeStatus.WAITING);
        assertThat(result.selectedRoutes()).containsExactly(entry);
        assertThat(result.activation().traversedRouteKeys()).containsExactly("toBranch", "leftRoute");
    }

    @Test
    void shouldUseStructuredSelectionsForMultipleReachableProgressionManualBranches() {
        WorkflowRuntimeProgressionService service = service(Optional.empty());
        WorkflowInstance instance = instance();
        WorkflowNodeInstance approve = node("node-approve", "approve", WorkflowNodeType.APPROVAL,
                WorkflowNodeStatus.COMPLETED);
        WorkflowNodeInstance split = node("node-split", "split", WorkflowNodeType.MILESTONE,
                WorkflowNodeStatus.WAITING);
        WorkflowNodeInstance branchA = manualBranch("node-branch-a", "branchA", "approve", true);
        WorkflowNodeInstance branchB = manualBranch("node-branch-b", "branchB", "approve", true);
        WorkflowNodeInstance taskA1 = node("node-task-a1", "taskA1", WorkflowNodeType.TASK,
                WorkflowNodeStatus.WAITING);
        WorkflowNodeInstance taskA2 = node("node-task-a2", "taskA2", WorkflowNodeType.TASK,
                WorkflowNodeStatus.WAITING);
        WorkflowNodeInstance taskB1 = node("node-task-b1", "taskB1", WorkflowNodeType.TASK,
                WorkflowNodeStatus.WAITING);
        WorkflowNodeInstance taskB2 = node("node-task-b2", "taskB2", WorkflowNodeType.TASK,
                WorkflowNodeStatus.WAITING);
        WorkflowRouteInstance entry = route("route-entry", "toSplit", "approve", "split", true);
        WorkflowRouteInstance toBranchA = route("route-to-a", "toBranchA", "split", "branchA", false);
        WorkflowRouteInstance toBranchB = route("route-to-b", "toBranchB", "split", "branchB", false);
        WorkflowRouteInstance routeA1 = route("route-a1", "routeA1", "branchA", "taskA1", false);
        WorkflowRouteInstance routeA2 = route("route-a2", "routeA2", "branchA", "taskA2", true);
        WorkflowRouteInstance routeB1 = route("route-b1", "routeB1", "branchB", "taskB1", false);
        WorkflowRouteInstance routeB2 = route("route-b2", "routeB2", "branchB", "taskB2", true);
        WorkflowTask approvedTask = task("task-approve", approve, "approver-1");
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(nodeDao.query(any(), any())).thenReturn(List.of(approve, split, branchA, branchB,
                taskA1, taskA2, taskB1, taskB2));
        when(routeDao.query(any(), any())).thenReturn(List.of(entry, toBranchA, toBranchB,
                routeA1, routeA2, routeB1, routeB2));
        when(taskDao.query(any(), any())).thenReturn(List.of(approvedTask));
        when(instanceDao.updateByIdAndVersion(instance, 5)).thenReturn(1);
        for (WorkflowNodeInstance node : List.of(approve, split, branchA, branchB, taskA1, taskA2, taskB1, taskB2)) {
            when(nodeDao.updateByIdAndVersion(node, 2)).thenReturn(1);
        }
        for (WorkflowRouteInstance route : List.of(entry, toBranchA, toBranchB, routeA1, routeA2, routeB1, routeB2)) {
            when(routeDao.updateByIdAndVersion(route, 4)).thenReturn(1);
        }

        WorkflowProgressionResult result = service.advanceFromNode("instance-1", "approve", "approver-1",
                Instant.parse("2026-06-05T03:00:00Z"),
                List.of(new WorkflowManualRouteSelection("branchA", "routeA1", "choose A1"),
                        new WorkflowManualRouteSelection("branchB", "routeB2", "choose B2")));

        assertThat(routeA1.getRouteStatus()).isEqualTo(WorkflowRouteStatus.EFFECTIVE);
        assertThat(routeA1.getRouteReason()).isEqualTo(WorkflowRouteReason.MANUAL_SELECTED);
        assertThat(routeA1.getSelectedReason()).isEqualTo("choose A1");
        assertThat(routeB2.getRouteStatus()).isEqualTo(WorkflowRouteStatus.EFFECTIVE);
        assertThat(routeB2.getRouteReason()).isEqualTo(WorkflowRouteReason.MANUAL_SELECTED);
        assertThat(routeB2.getSelectedReason()).isEqualTo("choose B2");
        assertThat(routeA2.getRouteReason()).isEqualTo(WorkflowRouteReason.MANUAL_UNSELECTED);
        assertThat(routeB1.getRouteReason()).isEqualTo(WorkflowRouteReason.MANUAL_UNSELECTED);
        assertThat(taskA1.getNodeStatus()).isEqualTo(WorkflowNodeStatus.ACTIVE);
        assertThat(taskB2.getNodeStatus()).isEqualTo(WorkflowNodeStatus.ACTIVE);
        assertThat(result.selectedRoutes()).containsExactly(entry);
        assertThat(result.activation().traversedRouteKeys())
                .containsExactly("toSplit", "toBranchA", "toBranchB", "routeA1", "routeB2");
    }

    @Test
    void shouldRejectProgressionWhenReachedManualBranchHasNoSelectedRoute() {
        WorkflowRuntimeProgressionService service = service(Optional.empty());
        WorkflowInstance instance = instance();
        WorkflowNodeInstance approve = node("node-approve", "approve", WorkflowNodeType.APPROVAL,
                WorkflowNodeStatus.COMPLETED);
        WorkflowNodeInstance branch = manualBranch("node-branch", "branch", "approve", false);
        WorkflowNodeInstance left = node("node-left", "leftTask", WorkflowNodeType.TASK, WorkflowNodeStatus.WAITING);
        WorkflowNodeInstance right = node("node-right", "rightTask", WorkflowNodeType.TASK, WorkflowNodeStatus.WAITING);
        WorkflowRouteInstance entry = route("route-entry", "toBranch", "approve", "branch", true);
        WorkflowRouteInstance leftRoute = route("route-left", "leftRoute", "branch", "leftTask", false);
        WorkflowRouteInstance rightRoute = route("route-right", "rightRoute", "branch", "rightTask", true);
        WorkflowTask approvedTask = task("task-approve", approve, "approver-1");
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(nodeDao.query(any(), any())).thenReturn(List.of(approve, branch, left, right));
        when(routeDao.query(any(), any())).thenReturn(List.of(entry, leftRoute, rightRoute));
        when(taskDao.query(any(), any())).thenReturn(List.of(approvedTask));

        assertThatThrownBy(() -> service.advanceFromNode("instance-1", "approve", "approver-1",
                Instant.parse("2026-06-05T03:00:00Z")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("manual branch requires selected route: branch");
    }

    @Test
    void shouldAllowProgressionManualBranchWhenSelectorProcessorMatchesOperator() {
        WorkflowRuntimeProgressionService service = service(Optional.empty());
        WorkflowInstance instance = instance();
        WorkflowNodeInstance approve = node("node-approve", "approve", WorkflowNodeType.APPROVAL,
                WorkflowNodeStatus.COMPLETED);
        WorkflowNodeInstance branch = manualBranch("node-branch", "branch", "approve", false);
        WorkflowNodeInstance left = node("node-left", "leftTask", WorkflowNodeType.TASK, WorkflowNodeStatus.WAITING);
        WorkflowNodeInstance right = node("node-right", "rightTask", WorkflowNodeType.TASK, WorkflowNodeStatus.WAITING);
        WorkflowRouteInstance entry = route("route-entry", "toBranch", "approve", "branch", true);
        WorkflowRouteInstance leftRoute = route("route-left", "leftRoute", "branch", "leftTask", false);
        WorkflowRouteInstance rightRoute = route("route-right", "rightRoute", "branch", "rightTask", true);
        WorkflowTask approvedTask = task("task-approve", approve, "approver-1");
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(nodeDao.query(any(), any())).thenReturn(List.of(approve, branch, left, right));
        when(routeDao.query(any(), any())).thenReturn(List.of(entry, leftRoute, rightRoute));
        when(taskDao.query(any(), any())).thenReturn(List.of(approvedTask));
        when(instanceDao.updateByIdAndVersion(instance, 5)).thenReturn(1);
        when(nodeDao.updateByIdAndVersion(approve, 2)).thenReturn(1);
        when(nodeDao.updateByIdAndVersion(branch, 2)).thenReturn(1);
        when(nodeDao.updateByIdAndVersion(left, 2)).thenReturn(1);
        when(nodeDao.updateByIdAndVersion(right, 2)).thenReturn(1);
        when(routeDao.updateByIdAndVersion(entry, 4)).thenReturn(1);
        when(routeDao.updateByIdAndVersion(leftRoute, 4)).thenReturn(1);
        when(routeDao.updateByIdAndVersion(rightRoute, 4)).thenReturn(1);

        WorkflowProgressionResult result = service.advanceFromNode("instance-1", "approve", "approver-1",
                Instant.parse("2026-06-05T03:00:00Z"), "leftRoute", null);

        assertThat(entry.getRouteStatus()).isEqualTo(WorkflowRouteStatus.EFFECTIVE);
        assertThat(leftRoute.getRouteStatus()).isEqualTo(WorkflowRouteStatus.EFFECTIVE);
        assertThat(leftRoute.getRouteReason()).isEqualTo(WorkflowRouteReason.MANUAL_SELECTED);
        assertThat(leftRoute.getSelectedBy()).isEqualTo("approver-1");
        assertThat(rightRoute.getRouteStatus()).isEqualTo(WorkflowRouteStatus.INEFFECTIVE);
        assertThat(left.getNodeStatus()).isEqualTo(WorkflowNodeStatus.ACTIVE);
        assertThat(result.activation().traversedRouteKeys()).containsExactly("toBranch", "leftRoute");
    }

    @Test
    void shouldRejectProgressionManualBranchWhenSelectorProcessorDoesNotMatchOperator() {
        WorkflowRuntimeProgressionService service = service(Optional.empty());
        WorkflowInstance instance = instance();
        WorkflowNodeInstance approve = node("node-approve", "approve", WorkflowNodeType.APPROVAL,
                WorkflowNodeStatus.COMPLETED);
        WorkflowNodeInstance branch = manualBranch("node-branch", "branch", "approve", false);
        WorkflowNodeInstance left = node("node-left", "leftTask", WorkflowNodeType.TASK, WorkflowNodeStatus.WAITING);
        WorkflowRouteInstance entry = route("route-entry", "toBranch", "approve", "branch", true);
        WorkflowRouteInstance leftRoute = route("route-left", "leftRoute", "branch", "leftTask", false);
        WorkflowTask approvedTask = task("task-approve", approve, "approver-1");
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(nodeDao.query(any(), any())).thenReturn(List.of(approve, branch, left));
        when(routeDao.query(any(), any())).thenReturn(List.of(entry, leftRoute));
        when(taskDao.query(any(), any())).thenReturn(List.of(approvedTask));

        assertThatThrownBy(() -> service.advanceFromNode("instance-1", "approve", "other-user",
                Instant.parse("2026-06-05T03:00:00Z"), "leftRoute", null))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("manual branch selector must be operator: branch");
    }

    @Test
    void shouldRejectProgressionManualBranchWhenSelectorNodeIsNotActionNode() {
        WorkflowRuntimeProgressionService service = service(Optional.empty());
        WorkflowInstance instance = instance();
        WorkflowNodeInstance approve = node("node-approve", "approve", WorkflowNodeType.APPROVAL,
                WorkflowNodeStatus.COMPLETED);
        WorkflowNodeInstance selector = node("node-selector", "selectorBranch", WorkflowNodeType.BRANCH,
                WorkflowNodeStatus.WAITING);
        WorkflowNodeInstance branch = manualBranch("node-branch", "branch", "selectorBranch", false);
        WorkflowNodeInstance left = node("node-left", "leftTask", WorkflowNodeType.TASK, WorkflowNodeStatus.WAITING);
        WorkflowRouteInstance entry = route("route-entry", "toBranch", "approve", "branch", true);
        WorkflowRouteInstance leftRoute = route("route-left", "leftRoute", "branch", "leftTask", false);
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(nodeDao.query(any(), any())).thenReturn(List.of(approve, selector, branch, left));
        when(routeDao.query(any(), any())).thenReturn(List.of(entry, leftRoute));
        when(taskDao.query(any(), any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.advanceFromNode("instance-1", "approve", "user-1",
                Instant.parse("2026-06-05T03:00:00Z"), "leftRoute", null))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("selector node must be start, approval or task: selectorBranch");
    }

    @Test
    void shouldRejectSelectedRouteKeyWhenRouteIsNotCandidateOutgoingRoute() {
        WorkflowRuntimeProgressionService service = service(Optional.empty());
        WorkflowInstance instance = instance();
        WorkflowNodeInstance branch = node("node-branch", "branch", WorkflowNodeType.BRANCH,
                WorkflowNodeStatus.COMPLETED);
        WorkflowNodeInstance left = node("node-left", "leftTask", WorkflowNodeType.TASK, WorkflowNodeStatus.WAITING);
        WorkflowRouteInstance leftRoute = route("route-left", "leftRoute", "branch", "leftTask", false);
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(nodeDao.query(any(), any())).thenReturn(List.of(branch, left));
        when(routeDao.query(any(), any())).thenReturn(List.of(leftRoute));

        assertThatThrownBy(() -> service.advanceFromNode("instance-1", "branch", "user-1",
                Instant.parse("2026-06-05T03:00:00Z"), "missingRoute"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("selected route is not candidate outgoing route");
    }

    private WorkflowRuntimeProgressionService service(Optional<WorkflowApprovalSummaryWriter> writer) {
        return new WorkflowRuntimeProgressionService(instanceDao,
                nodeDao,
                routeDao,
                taskDao,
                eventDao,
                new WorkflowRuntimeActivationService(),
                new WorkflowInstanceStateService(),
                new WorkflowNodeInstanceStateService(),
                new WorkflowRouteInstanceStateService(),
                new WorkflowRouteRuntimeService(),
                new WorkflowRuntimeTaskFactory(eventFactory,
                java.util.Optional.empty(),
                WorkflowTestSupport.participants()),
                eventFactory,
                writer,
                WorkflowTestSupport.facts());
    }

    private WorkflowInstance instance() {
        WorkflowInstance instance = new WorkflowInstance();
        instance.setId("instance-1");
        instance.setTenantId("tenant-1");
        instance.setVersion(5);
        instance.setModuleAlias("sales.contract");
        instance.setRecordId("record-1");
        instance.setInstanceStatus(WorkflowInstanceStatus.RUNNING);
        instance.setApprovalEnabled(false);
        instance.setApprovalStatus(WorkflowApprovalStatus.NONE);
        instance.setStartedBy("starter-1");
        instance.setStartedAt(Instant.parse("2026-06-05T01:00:00Z"));
        instance.setSnapshotText("{}");
        return instance;
    }

    private WorkflowNodeInstance node(String id, String key, WorkflowNodeType type, WorkflowNodeStatus status) {
        WorkflowNodeInstance node = new WorkflowNodeInstance();
        node.setId(id);
        node.setTenantId("tenant-1");
        node.setVersion(2);
        node.setInstanceId("instance-1");
        node.setNodeKey(key);
        node.setNodeRunId(key + ":1");
        node.setNodeType(type);
        node.setParticipantPolicyText("user:user-1");
        node.setNodeStatus(status);
        return node;
    }

    private WorkflowNodeInstance manualBranch(String id, String key, String selectorNodeKey, boolean requireReason) {
        WorkflowNodeInstance node = node(id, key, WorkflowNodeType.BRANCH, WorkflowNodeStatus.WAITING);
        node.setRouteMode(WorkflowRouteMode.MANUAL);
        node.setSelectorNodeKey(selectorNodeKey);
        node.setRequireManualSelectionReason(requireReason);
        return node;
    }

    private WorkflowTask task(String id, WorkflowNodeInstance node, String actualProcessorId) {
        WorkflowTask task = new WorkflowTask();
        task.setId(id);
        task.setTenantId("tenant-1");
        task.setVersion(3);
        task.setInstanceId("instance-1");
        task.setNodeInstanceId(node.getId());
        task.setTaskKind(node.getNodeType() == WorkflowNodeType.TASK ? WorkflowTaskKind.BUSINESS : WorkflowTaskKind.APPROVAL);
        task.setTaskStatus(WorkflowTaskStatus.DONE);
        task.setActualProcessorId(actualProcessorId);
        task.setCompletedAt(Instant.parse("2026-06-05T02:00:00Z"));
        return task;
    }

    private WorkflowRouteInstance route(String id, String key, String source, String target, boolean defaultRoute) {
        WorkflowRouteInstance route = new WorkflowRouteInstance();
        route.setId(id);
        route.setTenantId("tenant-1");
        route.setVersion(4);
        route.setInstanceId("instance-1");
        route.setRouteKey(key);
        route.setRouteRunId(key + ":1");
        route.setSourceNodeKey(source);
        route.setTargetNodeKey(target);
        route.setRouteStatus(WorkflowRouteStatus.CANDIDATE);
        route.setDefaultRoute(defaultRoute);
        return route;
    }
}
