package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.CriteriaOperator;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowHistoryQueryServiceTest {
    @org.junit.jupiter.api.BeforeEach
    void installWorkflowMutationHost() { WorkflowTestMutationHost.install(); }
    @org.junit.jupiter.api.AfterEach
    void resetWorkflowMutationHost() { WorkflowTestMutationHost.reset(); }

    private final WorkflowHistoryInstanceDao historyDao = mock(WorkflowHistoryInstanceDao.class);
    private final WorkflowArchiveService archiveService = mock(WorkflowArchiveService.class);
    private final WorkflowActionPolicyService actionPolicyService = mock(WorkflowActionPolicyService.class);
    private final WorkflowHistoryQueryService service = new WorkflowHistoryQueryService(
            historyDao, archiveService, actionPolicyService);

    @Test
    void shouldQueryRecordHistoryByModuleAndRecord() {
        when(historyDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class), any(Sort.class)))
                .thenReturn(List.of());

        service.queryRecordHistory("sales.contract", "record-1", PageRequest.of(1, 20));

        verify(actionPolicyService).requireRecordView(org.mockito.ArgumentMatchers.argThat(instance ->
                "sales.contract".equals(instance.getModuleAlias())
                        && "record-1".equals(instance.getRecordId())));
        verify(historyDao).query(any(Criteria.class), any(PageRequest.class), any(Sort.class), any(Sort.class));
    }

    @Test
    void shouldQueryRecordHistoryByStartedByWhenProvided() {
        when(historyDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class), any(Sort.class)))
                .thenReturn(List.of(history("history-1")));

        List<WorkflowHistoryInstance> histories = service.queryRecordHistory(
                "sales.contract", "record-1", "starter-1", PageRequest.of(1, 20));

        assertThat(histories).extracting(WorkflowHistoryInstance::getId).containsExactly("history-1");
        verify(actionPolicyService).requireRecordView(org.mockito.ArgumentMatchers.argThat(instance ->
                "sales.contract".equals(instance.getModuleAlias())
                        && "record-1".equals(instance.getRecordId())));
        ArgumentCaptor<Criteria> criteria = ArgumentCaptor.forClass(Criteria.class);
        verify(historyDao).query(criteria.capture(), any(PageRequest.class), any(Sort.class), any(Sort.class));
        assertThat(criteria.getValue().getClauses())
                .anySatisfy(clause -> {
                    assertThat(clause.getField()).isEqualTo("startedBy");
                    assertThat(clause.getOperator()).isEqualTo(CriteriaOperator.EQ);
                    assertThat(clause.getValues()).containsExactly("starter-1");
                });
    }

    @Test
    void shouldQueryAdminHistoryThroughManagementQueryAction() {
        when(historyDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class), any(Sort.class)))
                .thenReturn(List.of(history("history-1")));

        List<WorkflowHistoryInstance> histories = service.queryAdminHistory(
                "sales.contract", null, PageRequest.of(1, 20));

        assertThat(histories).extracting(WorkflowHistoryInstance::getId).containsExactly("history-1");
        verify(actionPolicyService).requireManagementAction(WorkflowActionPolicyService.MANAGEMENT_QUERY_ACTION);
        verify(historyDao).query(any(Criteria.class), any(PageRequest.class), any(Sort.class), any(Sort.class));
    }

    @Test
    void shouldQueryAdminHistoryByStartedByWhenProvided() {
        when(historyDao.query(any(Criteria.class), any(PageRequest.class), any(Sort.class), any(Sort.class)))
                .thenReturn(List.of(history("history-1")));

        List<WorkflowHistoryInstance> histories = service.queryAdminHistory(
                "sales.contract", null, "starter-1", PageRequest.of(1, 20));

        assertThat(histories).extracting(WorkflowHistoryInstance::getId).containsExactly("history-1");
        verify(actionPolicyService).requireManagementAction(WorkflowActionPolicyService.MANAGEMENT_QUERY_ACTION);
        ArgumentCaptor<Criteria> criteria = ArgumentCaptor.forClass(Criteria.class);
        verify(historyDao).query(criteria.capture(), any(PageRequest.class), any(Sort.class), any(Sort.class));
        assertThat(criteria.getValue().getClauses())
                .anySatisfy(clause -> {
                    assertThat(clause.getField()).isEqualTo("startedBy");
                    assertThat(clause.getOperator()).isEqualTo(CriteriaOperator.EQ);
                    assertThat(clause.getValues()).containsExactly("starter-1");
                });
    }

    @Test
    void shouldReadAdminHistoryDetailsThroughManagementQueryAction() {
        WorkflowHistoryInstance history = history("history-1");
        WorkflowEvent event = new WorkflowEvent();
        event.setId("event-1");
        when(historyDao.findById("history-1")).thenReturn(history);
        when(archiveService.parseSnapshot(history)).thenReturn(snapshot(task("task-1", WorkflowTaskStatus.DONE),
                List.of(event)));

        assertThat(service.renderAdminBundle("history-1").mode()).isEqualTo("HISTORY");
        assertThat(service.adminEvents("history-1")).containsExactly(event);
        assertThat(service.adminEventViews("history-1")).hasSize(1);

        verify(actionPolicyService, org.mockito.Mockito.times(3))
                .requireManagementAction(WorkflowActionPolicyService.MANAGEMENT_QUERY_ACTION);
    }

    @Test
    void shouldReadRecordHistoryDetailsThroughRecordViewAction() {
        WorkflowHistoryInstance history = history("history-1");
        WorkflowEvent event = new WorkflowEvent();
        event.setId("event-1");
        when(historyDao.findById("history-1")).thenReturn(history);
        when(archiveService.parseSnapshot(history)).thenReturn(snapshot(task("task-1", WorkflowTaskStatus.DONE),
                List.of(event)));

        assertThat(service.renderBundle("history-1").mode()).isEqualTo("HISTORY");
        assertThat(service.tasks("history-1")).hasSize(1);
        assertThat(service.taskViews("history-1")).hasSize(1);
        assertThat(service.events("history-1")).containsExactly(event);
        assertThat(service.eventViews("history-1")).hasSize(1);

        verify(actionPolicyService, org.mockito.Mockito.times(5))
                .requireRecordView(org.mockito.ArgumentMatchers.argThat(instance ->
                        "sales.contract".equals(instance.getModuleAlias())
                                && "record-1".equals(instance.getRecordId())));
    }

    @Test
    void shouldFallbackToHistoryRenderJsonWhenSnapshotInstanceDoesNotCarryIt() {
        WorkflowHistoryInstance history = history("history-1");
        history.setSemanticJson("{\"nodes\":[\"history\"]}");
        history.setLayoutJson("{\"zoom\":1}");
        WorkflowInstance snapshotInstance = new WorkflowInstance();
        snapshotInstance.setSemanticJson(null);
        snapshotInstance.setLayoutJson(null);
        when(historyDao.findById("history-1")).thenReturn(history);
        when(archiveService.parseSnapshot(history)).thenReturn(new WorkflowHistorySnapshot(1,
                Instant.parse("2026-06-05T04:00:00Z"), WorkflowArchiveReason.RECALLED,
                snapshotInstance, List.of(), List.of(), List.of(), List.of()));

        WorkflowRuntimeRenderBundle bundle = service.renderBundle("history-1");

        assertThat(bundle.semanticJson()).isEqualTo("{\"nodes\":[\"history\"]}");
        assertThat(bundle.layoutJson()).isEqualTo("{\"zoom\":1}");
        verify(actionPolicyService).requireRecordView(org.mockito.ArgumentMatchers.any(WorkflowInstance.class));
    }

    @Test
    void shouldRejectMissingHistoryInstance() {
        assertThatThrownBy(() -> service.renderBundle("missing"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("workflow history instance not found");
    }

    @Test
    void shouldDeleteAdminHistoryThroughDeleteHistoryAction() {
        WorkflowHistoryInstance history = history("history-1");
        when(historyDao.findById("history-1")).thenReturn(history);
        when(historyDao.deleteById("history-1")).thenReturn(1);

        int deleted = service.deleteHistory("history-1");

        assertThat(deleted).isEqualTo(1);
        verify(actionPolicyService).requireManagementAction(
                WorkflowActionPolicyService.MANAGEMENT_DELETE_HISTORY_ACTION);
        verify(historyDao).deleteById("history-1");
    }

    @Test
    void shouldRejectDeletingMissingAdminHistoryBeforeHardDelete() {
        assertThatThrownBy(() -> service.deleteHistory("missing"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("workflow history instance not found");

        verify(actionPolicyService).requireManagementAction(
                WorkflowActionPolicyService.MANAGEMENT_DELETE_HISTORY_ACTION);
        verify(historyDao, never()).deleteById("missing");
    }

    @Test
    void shouldExposeHistoryTaskExplanationFieldsFromSnapshot() {
        WorkflowHistoryInstance history = history("history-1");
        WorkflowTask task = task("task-1", WorkflowTaskStatus.CANCELED);
        when(historyDao.findById("history-1")).thenReturn(history);
        when(archiveService.parseSnapshot(history)).thenReturn(snapshot(task, List.of()));

        List<WorkflowHistoryTaskView> views = service.taskViews("history-1");

        assertThat(views).hasSize(1);
        assertThat(views.getFirst().actualProcessUserId()).isEqualTo("delegate-1");
        assertThat(views.getFirst().processedByDelegation()).isTrue();
        assertThat(views.getFirst().assignmentKind()).isEqualTo(WorkflowAssignmentKind.DELEGATED);
        assertThat(views.getFirst().originalAssigneeId()).isEqualTo("principal-1");
        assertThat(views.getFirst().delegatedFromUserId()).isEqualTo("principal-1");
        assertThat(views.getFirst().delegatedToUserId()).isEqualTo("delegate-1");
        assertThat(views.getFirst().principalCanProcess()).isTrue();
        assertThat(views.getFirst().delegationPolicyId()).isEqualTo("delegation-1");
        assertThat(views.getFirst().delegationSnapshot()).contains("delegate-1");
        assertThat(views.getFirst().canceled()).isTrue();
        assertThat(views.getFirst().invalidated()).isFalse();
    }

    @Test
    void shouldExposeHistoryEventExplanationFieldsFromRelatedTask() {
        WorkflowHistoryInstance history = history("history-1");
        WorkflowTask task = task("task-1", WorkflowTaskStatus.INVALIDATED);
        WorkflowEvent event = new WorkflowEvent();
        event.setId("event-1");
        event.setInstanceId("instance-1");
        event.setTaskId("task-1");
        event.setEventType(WorkflowEventType.TASK_INVALIDATED);
        event.setActionCode("invalidate");
        event.setOperatorId("delegate-1");
        event.setOccurredAt(Instant.parse("2026-06-05T03:00:00Z"));
        when(historyDao.findById("history-1")).thenReturn(history);
        when(archiveService.parseSnapshot(history)).thenReturn(snapshot(task, List.of(event)));

        List<WorkflowHistoryEventView> views = service.eventViews("history-1");

        assertThat(views).hasSize(1);
        assertThat(views.getFirst().actualProcessUserId()).isEqualTo("delegate-1");
        assertThat(views.getFirst().processedByDelegation()).isTrue();
        assertThat(views.getFirst().assignmentKind()).isEqualTo(WorkflowAssignmentKind.DELEGATED);
        assertThat(views.getFirst().delegationPolicyId()).isEqualTo("delegation-1");
        assertThat(views.getFirst().taskInvalidated()).isTrue();
        assertThat(views.getFirst().taskCanceled()).isFalse();
    }

    @Test
    void shouldExposeAddSignOriginFromAddSignEventPayload() {
        WorkflowHistoryInstance history = history("history-1");
        WorkflowNodeInstance source = node("node-source", "approve_source", false, null);
        WorkflowEvent event = event("event-add-sign", WorkflowEventType.ADD_SIGN, null,
                "{\"sourceNodeKey\":\"approve_source\",\"addedNodeKeys\":[\"approve_added\"]}");
        when(historyDao.findById("history-1")).thenReturn(history);
        when(archiveService.parseSnapshot(history)).thenReturn(snapshot(List.of(source), List.of(), List.of(), List.of(event)));

        List<WorkflowHistoryEventView> views = service.eventViews("history-1");

        assertThat(views).hasSize(1);
        assertThat(views.getFirst().originType()).isEqualTo(WorkflowHistoryEventView.ORIGIN_TYPE_ADD_SIGN);
        assertThat(views.getFirst().isAddSignRoute()).isFalse();
        assertThat(views.getFirst().addSignSourceNodeKey()).isEqualTo("approve_source");
        assertThat(views.getFirst().addSignSourceNodeName()).isEqualTo("approve_source");
    }

    @Test
    void shouldExposeAddSignOriginFromRouteSnapshot() {
        WorkflowHistoryInstance history = history("history-1");
        WorkflowNodeInstance source = node("node-source", "approve_source", false, null);
        WorkflowRouteInstance route = route("route-1", "add_route", true, "approve_source");
        WorkflowEvent event = event("event-route-dropped", WorkflowEventType.ROUTE_DROPPED, null,
                "{\"routeId\":\"route-1\",\"routeKey\":\"add_route\",\"addedByAddSign\":true,\"isAddSignRoute\":true}");
        when(historyDao.findById("history-1")).thenReturn(history);
        when(archiveService.parseSnapshot(history)).thenReturn(snapshot(List.of(source), List.of(route), List.of(), List.of(event)));

        List<WorkflowHistoryEventView> views = service.eventViews("history-1");

        assertThat(views.getFirst().originType()).isEqualTo(WorkflowHistoryEventView.ORIGIN_TYPE_ADD_SIGN);
        assertThat(views.getFirst().isAddSignRoute()).isTrue();
        assertThat(views.getFirst().addSignSourceNodeKey()).isEqualTo("approve_source");
        assertThat(views.getFirst().addSignSourceNodeName()).isEqualTo("approve_source");
    }

    @Test
    void shouldKeepDefinitionOriginForNormalRoutePayloadWithSourceNodeKey() {
        WorkflowHistoryInstance history = history("history-1");
        WorkflowRouteInstance route = route("route-1", "normal_route", false, null);
        String payload = "{\"routeId\":\"route-1\",\"routeKey\":\"normal_route\",\"sourceNodeKey\":\"approve\","
                + "\"targetNodeKey\":\"next\",\"addedByAddSign\":false,\"isAddSignRoute\":false}";
        WorkflowEvent selected = event("event-route-selected", WorkflowEventType.ROUTE_SELECTED, null, payload);
        WorkflowEvent dropped = event("event-route-dropped", WorkflowEventType.ROUTE_DROPPED, null, payload);
        when(historyDao.findById("history-1")).thenReturn(history);
        when(archiveService.parseSnapshot(history)).thenReturn(snapshot(List.of(), List.of(route), List.of(),
                List.of(selected, dropped)));

        List<WorkflowHistoryEventView> views = service.eventViews("history-1");

        assertThat(views).hasSize(2);
        assertThat(views).allSatisfy(view -> {
            assertThat(view.originType()).isEqualTo(WorkflowHistoryEventView.ORIGIN_TYPE_DEFINITION);
            assertThat(view.isAddSignRoute()).isFalse();
            assertThat(view.addSignSourceNodeKey()).isNull();
            assertThat(view.addSignSourceNodeName()).isNull();
        });
    }

    @Test
    void shouldExposeAddSignOriginFromAssociatedEventNode() {
        WorkflowHistoryInstance history = history("history-1");
        WorkflowNodeInstance source = node("node-source", "approve_source", false, null);
        WorkflowNodeInstance added = node("node-added", "approve_added", true, "approve_source");
        WorkflowEvent event = event("event-task-created", WorkflowEventType.TASK_CREATED, "node-added", null);
        when(historyDao.findById("history-1")).thenReturn(history);
        when(archiveService.parseSnapshot(history)).thenReturn(snapshot(List.of(source, added), List.of(), List.of(), List.of(event)));

        List<WorkflowHistoryEventView> views = service.eventViews("history-1");

        assertThat(views.getFirst().originType()).isEqualTo(WorkflowHistoryEventView.ORIGIN_TYPE_ADD_SIGN);
        assertThat(views.getFirst().isAddSignRoute()).isTrue();
        assertThat(views.getFirst().addSignSourceNodeKey()).isEqualTo("approve_source");
    }

    @Test
    void shouldKeepDefinitionOriginWhenPayloadIsInvalid() {
        WorkflowHistoryInstance history = history("history-1");
        WorkflowEvent event = event("event-bad-payload", WorkflowEventType.ROUTE_DROPPED, null, "{bad json");
        when(historyDao.findById("history-1")).thenReturn(history);
        when(archiveService.parseSnapshot(history)).thenReturn(snapshot(List.of(), List.of(), List.of(), List.of(event)));

        List<WorkflowHistoryEventView> views = service.eventViews("history-1");

        assertThat(views.getFirst().originType()).isEqualTo(WorkflowHistoryEventView.ORIGIN_TYPE_DEFINITION);
        assertThat(views.getFirst().isAddSignRoute()).isFalse();
        assertThat(views.getFirst().addSignSourceNodeKey()).isNull();
        assertThat(views.getFirst().addSignSourceNodeName()).isNull();
    }

    private WorkflowHistoryInstance history(String id) {
        WorkflowHistoryInstance history = new WorkflowHistoryInstance();
        history.setId(id);
        history.setModuleAlias("sales.contract");
        history.setRecordId("record-1");
        return history;
    }

    private WorkflowTask task(String id, WorkflowTaskStatus status) {
        WorkflowTask task = new WorkflowTask();
        task.setId(id);
        task.setInstanceId("instance-1");
        task.setNodeInstanceId("node-1");
        task.setTaskKind(WorkflowTaskKind.APPROVAL);
        task.setTaskStatus(status);
        task.setAssignmentKind(WorkflowAssignmentKind.DELEGATED);
        task.setOriginalAssigneeId("principal-1");
        task.setAssigneeId("delegate-1");
        task.setActualProcessorId("delegate-1");
        task.setDelegatedFromUserId("principal-1");
        task.setDelegatedToUserId("delegate-1");
        task.setPrincipalCanProcess(true);
        task.setDelegationPolicyId("delegation-1");
        task.setAssignmentSnapshotText("{\"delegateUserId\":\"delegate-1\"}");
        task.setDecision("cancel");
        task.setCreatedAt(Instant.parse("2026-06-05T01:00:00Z"));
        task.setCompletedAt(Instant.parse("2026-06-05T02:00:00Z"));
        return task;
    }

    private WorkflowEvent event(String id, WorkflowEventType eventType, String nodeInstanceId, String payloadText) {
        WorkflowEvent event = new WorkflowEvent();
        event.setId(id);
        event.setInstanceId("instance-1");
        event.setNodeInstanceId(nodeInstanceId);
        event.setEventType(eventType);
        event.setActionCode(eventType == WorkflowEventType.ADD_SIGN ? "addSign" : "route");
        event.setOperatorId("user-1");
        event.setPayloadText(payloadText);
        event.setOccurredAt(Instant.parse("2026-06-05T03:00:00Z"));
        return event;
    }

    private WorkflowNodeInstance node(String id, String nodeKey, boolean addedByAddSign, String sourceNodeKey) {
        WorkflowNodeInstance node = new WorkflowNodeInstance();
        node.setId(id);
        node.setInstanceId("instance-1");
        node.setNodeKey(nodeKey);
        node.setNodeRunId(nodeKey + ":run");
        node.setNodeType(WorkflowNodeType.APPROVAL);
        node.setAddedByAddSign(addedByAddSign);
        node.setAddSignSourceNodeKey(sourceNodeKey);
        return node;
    }

    private WorkflowRouteInstance route(String id, String routeKey, boolean addedByAddSign, String sourceNodeKey) {
        WorkflowRouteInstance route = new WorkflowRouteInstance();
        route.setId(id);
        route.setInstanceId("instance-1");
        route.setRouteKey(routeKey);
        route.setRouteRunId(routeKey + ":run");
        route.setSourceNodeKey(sourceNodeKey);
        route.setTargetNodeKey("approve_added");
        route.setAddedByAddSign(addedByAddSign);
        route.setAddSignSourceNodeKey(sourceNodeKey);
        return route;
    }

    private WorkflowHistorySnapshot snapshot(WorkflowTask task, List<WorkflowEvent> events) {
        return new WorkflowHistorySnapshot(1, Instant.parse("2026-06-05T04:00:00Z"),
                WorkflowArchiveReason.RECALLED, new WorkflowInstance(), List.of(), List.of(), List.of(task), events);
    }

    private WorkflowHistorySnapshot snapshot(List<WorkflowNodeInstance> nodes,
                                             List<WorkflowRouteInstance> routes,
                                             List<WorkflowTask> tasks,
                                             List<WorkflowEvent> events) {
        return new WorkflowHistorySnapshot(1, Instant.parse("2026-06-05T04:00:00Z"),
                WorkflowArchiveReason.RECALLED, new WorkflowInstance(), nodes, routes, tasks, events);
    }
}
