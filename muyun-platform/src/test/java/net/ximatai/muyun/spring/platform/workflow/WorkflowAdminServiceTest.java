package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkflowAdminServiceTest {
    @org.junit.jupiter.api.BeforeEach
    void installWorkflowMutationHost() { WorkflowTestMutationHost.install(); }
    @org.junit.jupiter.api.AfterEach
    void resetWorkflowMutationHost() { WorkflowTestMutationHost.reset(); }

    private final WorkflowInstanceDao instanceDao = mock(WorkflowInstanceDao.class);
    private final WorkflowTaskDao taskDao = mock(WorkflowTaskDao.class);
    private final WorkflowNodeInstanceDao nodeInstanceDao = mock(WorkflowNodeInstanceDao.class);
    private final WorkflowRouteInstanceDao routeInstanceDao = mock(WorkflowRouteInstanceDao.class);
    private final WorkflowEventDao eventDao = mock(WorkflowEventDao.class);
    private final WorkflowActionPolicyService actionPolicyService = mock(WorkflowActionPolicyService.class);
    private final WorkflowInstanceActionService instanceActionService = mock(WorkflowInstanceActionService.class);
    private final WorkflowTaskActionService taskActionService = mock(WorkflowTaskActionService.class);
    private final WorkflowHistoryQueryService historyQueryService = mock(WorkflowHistoryQueryService.class);
    private final WorkflowAdminService service = new WorkflowAdminService(instanceDao, taskDao, nodeInstanceDao,
            routeInstanceDao, eventDao, actionPolicyService, instanceActionService, taskActionService,
            historyQueryService, Optional.empty());

    @Test
    void shouldDefaultCurrentInstanceQueryToRunningInstances() {
        WorkflowInstance instance = instance(WorkflowInstanceStatus.RUNNING);
        instance.setDefinitionId("definition-1");
        instance.setWorkflowVersionId("version-1");
        instance.setVersionNo(3);
        instance.setStartedBy("starter-1");
        instance.setStartedAt(Instant.parse("2026-06-05T01:00:00Z"));
        instance.setUpdatedAt(Instant.parse("2026-06-05T02:00:00Z"));
        instance.setLastOperatedAt(Instant.parse("2026-06-05T02:30:00Z"));
        WorkflowTask task = task("task-1", "node-active", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        task.setAssigneeId("approver-1");
        WorkflowNodeInstance node = node("node-active", WorkflowNodeStatus.ACTIVE, WorkflowNodeType.APPROVAL);
        node.setNodeTitle("合同审批");
        node.setOvertimeStatus(WorkflowOvertimeStatus.NORMAL);
        when(instanceDao.query(any(), any(), any(), any(), any())).thenReturn(List.of(instance));
        when(taskDao.query(any(), any(), any())).thenReturn(List.of(task));
        when(nodeInstanceDao.query(any(), any(), any())).thenReturn(List.of(node));
        WorkflowAdminService serviceWithTitles = serviceWithTitles(Map.of(
                "starter-1", "发起人",
                "approver-1", "审批人"));

        List<WorkflowAdminInstanceView> views = serviceWithTitles.queryCurrentInstances(null, PageRequest.of(1, 20));

        assertThat(views).hasSize(1);
        WorkflowAdminInstanceView view = views.getFirst();
        assertThat(view.instanceId()).isEqualTo("instance-1");
        assertThat(view.moduleAlias()).isEqualTo("sales.contract");
        assertThat(view.recordId()).isEqualTo("record-1");
        assertThat(view.definitionId()).isEqualTo("definition-1");
        assertThat(view.workflowVersionId()).isEqualTo("version-1");
        assertThat(view.versionNo()).isEqualTo(3);
        assertThat(view.instanceStatus()).isEqualTo(WorkflowInstanceStatus.RUNNING);
        assertThat(view.startedBy()).isEqualTo("starter-1");
        assertThat(view.startedByTitle()).isEqualTo("发起人");
        assertThat(view.activeNodeKeys()).containsExactly("approve_1");
        assertThat(view.activeNodeTitles()).containsExactly("合同审批");
        assertThat(view.currentTaskIds()).containsExactly("task-1");
        assertThat(view.currentAssigneeIds()).containsExactly("approver-1");
        assertThat(view.currentAssigneeTitles()).containsExactly("审批人");
        assertThat(view.overtimeStatus()).isEqualTo(WorkflowOvertimeStatus.NORMAL);
        assertThat(view.updatedAt()).isEqualTo(Instant.parse("2026-06-05T02:00:00Z"));
        assertThat(view.lastOperatedAt()).isEqualTo(Instant.parse("2026-06-05T02:30:00Z"));
        verify(actionPolicyService).requireManagementAction(WorkflowActionPolicyService.MANAGEMENT_QUERY_ACTION);
        ArgumentCaptor<net.ximatai.muyun.database.core.orm.Criteria> criteriaCaptor =
                ArgumentCaptor.forClass(net.ximatai.muyun.database.core.orm.Criteria.class);
        verify(instanceDao).query(criteriaCaptor.capture(), any(), any(), any(), any());
        assertThat(criteriaCaptor.getValue().getClauses())
                .anySatisfy(clause -> {
                    assertThat(clause.getField()).isEqualTo("instanceStatus");
                    assertThat(clause.getValues()).containsExactly(WorkflowInstanceStatus.RUNNING);
                });
    }

    @Test
    void shouldFilterCurrentInstancesByCurrentAssigneeAndOvertime() {
        WorkflowInstance warned = instance(WorkflowInstanceStatus.RUNNING);
        WorkflowInstance normal = instance(WorkflowInstanceStatus.RUNNING);
        normal.setId("instance-2");
        when(instanceDao.query(any(), any(), any(), any(), any())).thenReturn(List.of(warned, normal));
        WorkflowTask delegated = task("task-1", "node-active", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        delegated.setAssigneeId("delegate-1");
        delegated.setAssignmentKind(WorkflowAssignmentKind.DELEGATED);
        delegated.setPrincipalCanProcess(true);
        delegated.setDelegatedFromUserId("principal-1");
        WorkflowTask normalTask = task("task-2", "node-normal", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        normalTask.setInstanceId("instance-2");
        normalTask.setAssigneeId("other-1");
        WorkflowNodeInstance warnedNode = node("node-active", WorkflowNodeStatus.ACTIVE, WorkflowNodeType.APPROVAL);
        warnedNode.setOvertimeStatus(WorkflowOvertimeStatus.WARNED);
        WorkflowNodeInstance normalNode = node("node-normal", WorkflowNodeStatus.ACTIVE, WorkflowNodeType.APPROVAL);
        normalNode.setInstanceId("instance-2");
        normalNode.setOvertimeStatus(WorkflowOvertimeStatus.NORMAL);
        when(taskDao.query(any(), any(), any())).thenReturn(List.of(delegated), List.of(normalTask));
        when(nodeInstanceDao.query(any(), any(), any())).thenReturn(List.of(warnedNode), List.of(normalNode));

        List<WorkflowAdminInstanceView> views = service.queryCurrentInstances(
                new WorkflowAdminInstanceQueryRequest(null, null, null, null, null,
                        "principal-1", WorkflowOvertimeStatus.WARNED),
                PageRequest.of(1, 20));

        assertThat(views).hasSize(1);
        assertThat(views.getFirst().instanceId()).isEqualTo("instance-1");
        assertThat(views.getFirst().currentAssigneeIds()).containsExactly("delegate-1", "principal-1");
        assertThat(views.getFirst().overtimeStatus()).isEqualTo(WorkflowOvertimeStatus.WARNED);
    }

    @Test
    void shouldQueryRawCurrentTodoTasksThroughTodoTaskQueryPolicy() {
        WorkflowInstance instance = instance(WorkflowInstanceStatus.RUNNING);
        WorkflowTask task = new WorkflowTask();
        task.setId("task-1");
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(taskDao.query(any(), any(), any())).thenReturn(List.of(task));

        List<WorkflowTask> tasks = service.currentTodoTasks("instance-1");

        assertThat(tasks).containsExactly(task);
        verify(actionPolicyService).requireManagementAction(
                WorkflowActionPolicyService.MANAGEMENT_TODO_TASK_QUERY_ACTION);
    }

    @Test
    void shouldQueryCurrentTodoTaskViewsThroughForceApprovePolicy() {
        WorkflowTask task = task("task-1", "node-active", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        WorkflowNodeInstance node = node("node-active", WorkflowNodeStatus.ACTIVE, WorkflowNodeType.APPROVAL);
        when(instanceDao.findById("instance-1")).thenReturn(instance(WorkflowInstanceStatus.RUNNING));
        when(taskDao.query(any(), any(), any())).thenReturn(List.of(task));
        when(nodeInstanceDao.findById("node-active")).thenReturn(node);

        List<WorkflowAdminActiveTaskView> views = service.currentTodoTaskViews("instance-1");

        assertThat(views).hasSize(1);
        verify(actionPolicyService).requireManagementAction(
                WorkflowActionPolicyService.MANAGEMENT_FORCE_APPROVE_ACTION);
    }

    @Test
    void shouldExposeActiveApprovalTodoTaskViewsOnly() {
        WorkflowInstance instance = instance(WorkflowInstanceStatus.RUNNING);
        WorkflowTask activeApproval = task("task-1", "node-active", WorkflowTaskKind.APPROVAL,
                WorkflowTaskStatus.TODO);
        activeApproval.setAssigneeId("delegate-1");
        activeApproval.setAssignmentKind(WorkflowAssignmentKind.DELEGATED);
        activeApproval.setOriginalAssigneeId("principal-1");
        activeApproval.setDelegatedFromUserId("principal-1");
        activeApproval.setDelegatedToUserId("delegate-1");
        activeApproval.setPrincipalCanProcess(true);
        activeApproval.setDelegationPolicyId("delegation-1");
        activeApproval.setAssignmentSnapshotText("{\"delegationPolicyId\":\"delegation-1\"}");
        activeApproval.setCreatedAt(Instant.parse("2026-06-05T01:00:00Z"));
        WorkflowTask notice = task("task-notice", "node-active", WorkflowTaskKind.NOTICE, WorkflowTaskStatus.TODO);
        WorkflowTask business = task("task-business", "node-active", WorkflowTaskKind.BUSINESS,
                WorkflowTaskStatus.TODO);
        WorkflowTask done = task("task-done", "node-active", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.DONE);
        WorkflowTask inactiveNodeTask = task("task-inactive", "node-inactive", WorkflowTaskKind.APPROVAL,
                WorkflowTaskStatus.TODO);
        WorkflowTask taskNodeTask = task("task-node", "node-task", WorkflowTaskKind.APPROVAL,
                WorkflowTaskStatus.TODO);
        WorkflowNodeInstance activeNode = node("node-active", WorkflowNodeStatus.ACTIVE, WorkflowNodeType.APPROVAL);
        activeNode.setOvertimeStatus(WorkflowOvertimeStatus.WARNED);
        activeNode.setAddedByAddSign(true);
        activeNode.setAddSignSourceNodeKey("approve_source");
        activeNode.setAddSignOperatorId("operator-1");
        activeNode.setAddSignAt(Instant.parse("2026-06-05T00:30:00Z"));
        WorkflowNodeInstance inactiveNode = node("node-inactive", WorkflowNodeStatus.COMPLETED,
                WorkflowNodeType.APPROVAL);
        WorkflowNodeInstance taskNode = node("node-task", WorkflowNodeStatus.ACTIVE, WorkflowNodeType.TASK);
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(taskDao.query(any(), any(), any())).thenReturn(List.of(
                activeApproval, notice, business, done, inactiveNodeTask, taskNodeTask));
        when(nodeInstanceDao.findById("node-active")).thenReturn(activeNode);
        when(nodeInstanceDao.findById("node-inactive")).thenReturn(inactiveNode);
        when(nodeInstanceDao.findById("node-task")).thenReturn(taskNode);

        List<WorkflowAdminActiveTaskView> views = service.currentTodoTaskViews("instance-1");

        assertThat(views).hasSize(1);
        WorkflowAdminActiveTaskView view = views.getFirst();
        assertThat(view.taskId()).isEqualTo("task-1");
        assertThat(view.instanceId()).isEqualTo("instance-1");
        assertThat(view.nodeInstanceId()).isEqualTo("node-active");
        assertThat(view.nodeKey()).isEqualTo("approve_1");
        assertThat(view.taskKind()).isEqualTo(WorkflowTaskKind.APPROVAL);
        assertThat(view.taskStatus()).isEqualTo(WorkflowTaskStatus.TODO);
        assertThat(view.assigneeId()).isEqualTo("delegate-1");
        assertThat(view.createdAt()).isEqualTo(Instant.parse("2026-06-05T01:00:00Z"));
        assertThat(view.receivedAt()).isEqualTo(Instant.parse("2026-06-05T01:00:00Z"));
        assertThat(view.overtimeStatus()).isEqualTo(WorkflowOvertimeStatus.WARNED);
        assertThat(view.canForceApprove()).isTrue();
        assertThat(view.assignmentKind()).isEqualTo(WorkflowAssignmentKind.DELEGATED);
        assertThat(view.originalAssigneeId()).isEqualTo("principal-1");
        assertThat(view.delegatedFromUserId()).isEqualTo("principal-1");
        assertThat(view.delegatedToUserId()).isEqualTo("delegate-1");
        assertThat(view.principalCanProcess()).isTrue();
        assertThat(view.delegationPolicyId()).isEqualTo("delegation-1");
        assertThat(view.delegationSnapshot()).contains("delegation-1");
        assertThat(view.addedByAddSign()).isTrue();
        assertThat(view.addSignSourceNodeKey()).isEqualTo("approve_source");
        assertThat(view.addSignOperatorId()).isEqualTo("operator-1");
        assertThat(view.addSignAt()).isEqualTo(Instant.parse("2026-06-05T00:30:00Z"));
        verify(actionPolicyService).requireManagementAction(
                WorkflowActionPolicyService.MANAGEMENT_FORCE_APPROVE_ACTION);
    }

    @Test
    void shouldDefaultActiveTaskAddSignFactsForNormalNodes() {
        WorkflowTask task = task("task-1", "node-active", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        WorkflowNodeInstance node = node("node-active", WorkflowNodeStatus.ACTIVE, WorkflowNodeType.APPROVAL);
        node.setAddSignSourceNodeKey("ignored_source");
        node.setAddSignOperatorId("ignored_operator");
        node.setAddSignAt(Instant.parse("2026-06-05T00:30:00Z"));
        when(instanceDao.findById("instance-1")).thenReturn(instance(WorkflowInstanceStatus.RUNNING));
        when(taskDao.query(any(), any(), any())).thenReturn(List.of(task));
        when(nodeInstanceDao.findById("node-active")).thenReturn(node);

        List<WorkflowAdminActiveTaskView> views = service.currentTodoTaskViews("instance-1");

        assertThat(views).hasSize(1);
        assertThat(views.getFirst().addedByAddSign()).isFalse();
        assertThat(views.getFirst().addSignSourceNodeKey()).isNull();
        assertThat(views.getFirst().addSignOperatorId()).isNull();
        assertThat(views.getFirst().addSignAt()).isNull();
    }

    @Test
    void shouldRejectTodoQueryForNonRunningInstance() {
        when(instanceDao.findById("instance-1")).thenReturn(instance(WorkflowInstanceStatus.COMPLETED));

        assertThatThrownBy(() -> service.currentTodoTasks("instance-1"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("not running");

        verifyNoInteractions(taskDao, nodeInstanceDao, actionPolicyService);
    }

    @Test
    void shouldDelegateManagementActions() {
        WorkflowInstanceActionRequest instanceRequest = WorkflowInstanceActionRequest.terminate(
                "instance-1", "admin-1", "force stop");
        WorkflowTaskActionRequest taskRequest = WorkflowTaskActionRequest.complete(
                "task-1", "admin-1", "force approve");

        service.reset(instanceRequest);
        service.forceTerminate(instanceRequest);
        service.forceApprove(taskRequest);

        verify(instanceActionService).managementReset(instanceRequest);
        verify(instanceActionService).forceTerminate(instanceRequest);
        verify(taskActionService).forceApprove(taskRequest);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(WorkflowInstanceStatus.class)
    void shouldReadCurrentInstanceDetailsThroughManagementQueryPolicy(WorkflowInstanceStatus status) {
        WorkflowInstance instance = instance(status);
        WorkflowNodeInstance node = node("node-active", WorkflowNodeStatus.ACTIVE, WorkflowNodeType.APPROVAL);
        WorkflowRouteInstance route = new WorkflowRouteInstance();
        route.setId("route-1");
        route.setInstanceId("instance-1");
        WorkflowTask task = task("task-1", "node-active", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO);
        WorkflowEvent event = new WorkflowEvent();
        event.setId("event-1");
        event.setInstanceId("instance-1");
        when(instanceDao.findById("instance-1")).thenReturn(instance);
        when(nodeInstanceDao.query(any(), any(), any())).thenReturn(List.of(node));
        when(routeInstanceDao.query(any(), any(), any())).thenReturn(List.of(route));
        when(taskDao.query(any(), any(), any())).thenReturn(List.of(task));
        when(eventDao.query(any(), any(), any(), any())).thenReturn(List.of(event));

        WorkflowRuntimeRenderBundle bundle = service.renderCurrentBundle("instance-1");

        assertThat(bundle.mode()).isEqualTo("RUNTIME");
        assertThat(bundle.instance()).isEqualTo(instance);
        assertThat(bundle.nodes()).containsExactly(node);
        assertThat(bundle.routes()).containsExactly(route);
        assertThat(service.currentTasks("instance-1")).containsExactly(task);
        assertThat(service.currentEvents("instance-1")).containsExactly(event);
        verify(actionPolicyService, times(3)).requireManagementAction(
                WorkflowActionPolicyService.MANAGEMENT_QUERY_ACTION);
    }

    @Test
    void shouldKeepActiveHandlingRestrictedToRunningInstances() {
        when(instanceDao.findById("instance-1")).thenReturn(instance(WorkflowInstanceStatus.COMPLETED));

        assertThatThrownBy(() -> service.currentTodoTaskViews("instance-1"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("not running");

        verifyNoInteractions(routeInstanceDao, eventDao, actionPolicyService);
    }

    @Test
    void shouldDelegateAdminHistoryContracts() {
        WorkflowRuntimeRenderBundle bundle = new WorkflowRuntimeRenderBundle("HISTORY", null, List.of(), List.of());
        PageRequest pageRequest = PageRequest.of(1, 20);
        when(historyQueryService.queryAdminHistory("sales.contract", "record-1", "starter-1", pageRequest))
                .thenReturn(List.of());
        when(historyQueryService.renderAdminBundle("history-1")).thenReturn(bundle);
        when(historyQueryService.adminEvents("history-1")).thenReturn(List.of());
        when(historyQueryService.adminEventViews("history-1")).thenReturn(List.of());
        when(historyQueryService.deleteHistory("history-1")).thenReturn(1);

        assertThat(service.queryHistory("sales.contract", "record-1", "starter-1", pageRequest)).isEmpty();
        assertThat(service.renderHistoryBundle("history-1")).isEqualTo(bundle);
        assertThat(service.historyEvents("history-1")).isEmpty();
        assertThat(service.historyEventViews("history-1")).isEmpty();
        assertThat(service.deleteHistory("history-1")).isEqualTo(1);

        verify(historyQueryService).queryAdminHistory("sales.contract", "record-1", "starter-1", pageRequest);
        verify(historyQueryService).renderAdminBundle("history-1");
        verify(historyQueryService).adminEvents("history-1");
        verify(historyQueryService).adminEventViews("history-1");
        verify(historyQueryService).deleteHistory("history-1");
    }

    private WorkflowInstance instance(WorkflowInstanceStatus status) {
        WorkflowInstance instance = new WorkflowInstance();
        instance.setId("instance-1");
        instance.setModuleAlias("sales.contract");
        instance.setRecordId("record-1");
        instance.setInstanceStatus(status);
        return instance;
    }

    private WorkflowTask task(String id, String nodeInstanceId, WorkflowTaskKind kind, WorkflowTaskStatus status) {
        WorkflowTask task = new WorkflowTask();
        task.setId(id);
        task.setInstanceId("instance-1");
        task.setNodeInstanceId(nodeInstanceId);
        task.setTaskKind(kind);
        task.setTaskStatus(status);
        return task;
    }

    private WorkflowNodeInstance node(String id, WorkflowNodeStatus status, WorkflowNodeType type) {
        WorkflowNodeInstance node = new WorkflowNodeInstance();
        node.setId(id);
        node.setInstanceId("instance-1");
        node.setNodeKey("approve_1");
        node.setNodeStatus(status);
        node.setNodeType(type);
        return node;
    }

    private WorkflowAdminService serviceWithTitles(Map<String, String> userTitles) {
        return new WorkflowAdminService(instanceDao, taskDao, nodeInstanceDao, routeInstanceDao, eventDao,
                actionPolicyService, instanceActionService, taskActionService, historyQueryService,
                Optional.of(userIds -> userTitles));
    }
}
