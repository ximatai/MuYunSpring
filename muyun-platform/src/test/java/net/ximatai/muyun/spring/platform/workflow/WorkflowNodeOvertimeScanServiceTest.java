package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.CriteriaClause;
import net.ximatai.muyun.database.core.orm.CriteriaOperator;
import net.ximatai.muyun.database.core.orm.PageRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkflowNodeOvertimeScanServiceTest {
    @org.junit.jupiter.api.BeforeEach
    void installWorkflowMutationHost() { WorkflowTestMutationHost.install(); }
    @org.junit.jupiter.api.AfterEach
    void resetWorkflowMutationHost() { WorkflowTestMutationHost.reset(); }

    private final WorkflowNodeInstanceDao nodeDao = mock(WorkflowNodeInstanceDao.class);
    private final WorkflowInstanceDao instanceDao = mock(WorkflowInstanceDao.class);
    private final WorkflowEventDao eventDao = mock(WorkflowEventDao.class);
    private final WorkflowRuntimeEventFactory eventFactory = new WorkflowRuntimeEventFactory();
    private final WorkflowNodeOvertimeScanService service = new WorkflowNodeOvertimeScanService(
            nodeDao, instanceDao, eventDao, eventFactory);

    @Test
    void shouldIgnoreActiveApprovalNodeBeforeThreshold() {
        WorkflowNodeInstance node = node();
        node.setActivatedAt(Instant.parse("2026-06-05T01:00:00Z"));
        node.setWarningDurationMinutes(30);
        node.setOvertimeDurationMinutes(60);
        when(nodeDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(node));

        WorkflowNodeOvertimeScanResult result = service.scan(Instant.parse("2026-06-05T01:20:00Z"));

        assertThat(result.scannedCount()).isEqualTo(1);
        assertThat(result.updatedCount()).isZero();
        assertThat(node.getOvertimeStatus()).isEqualTo(WorkflowOvertimeStatus.NORMAL);
        ArgumentCaptor<Criteria> criteriaCaptor = ArgumentCaptor.forClass(Criteria.class);
        verify(nodeDao).query(criteriaCaptor.capture(), any(PageRequest.class));
        assertThat(criteriaCaptor.getValue().getClauses())
                .anySatisfy(clause -> assertClause(clause, "nodeStatus", WorkflowNodeStatus.ACTIVE))
                .anySatisfy(clause -> assertClause(clause, "nodeType", WorkflowNodeType.APPROVAL));
        verify(nodeDao, never()).updateByIdAndVersion(any(), any());
        verifyNoInteractions(instanceDao, eventDao);
    }

    @Test
    void shouldMarkWarnedOnceAndWriteEvent() {
        WorkflowNodeInstance node = node();
        node.setActivatedAt(Instant.parse("2026-06-05T01:00:00Z"));
        node.setWarningDurationMinutes(30);
        node.setOvertimeDurationMinutes(90);
        WorkflowInstance instance = instance();
        when(nodeDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(node));
        when(nodeDao.updateByIdAndVersion(node, 2)).thenReturn(1);
        when(instanceDao.findById("instance-1")).thenReturn(instance);

        WorkflowNodeOvertimeScanResult result = service.scan(Instant.parse("2026-06-05T01:30:00Z"));

        assertThat(result.warnedCount()).isEqualTo(1);
        assertThat(result.overdueCount()).isZero();
        assertThat(node.getOvertimeStatus()).isEqualTo(WorkflowOvertimeStatus.WARNED);
        assertThat(node.getWarnedAt()).isEqualTo(Instant.parse("2026-06-05T01:30:00Z"));
        verify(nodeDao).updateByIdAndVersion(node, 2);
        ArgumentCaptor<WorkflowEvent> eventCaptor = ArgumentCaptor.forClass(WorkflowEvent.class);
        verify(eventDao).insert(eventCaptor.capture());
        WorkflowEvent event = eventCaptor.getValue();
        assertThat(event.getEventType()).isEqualTo(WorkflowEventType.OVERTIME_WARNED);
        assertThat(event.getPayloadText())
                .contains("\"nodeInstanceId\":\"node-1\"")
                .contains("\"triggerMinutes\":30")
                .contains("\"currentStatus\":\"warned\"");
    }

    @Test
    void shouldMarkOverdueBeforeWarningWhenBothThresholdsReached() {
        WorkflowNodeInstance node = node();
        node.setActivatedAt(Instant.parse("2026-06-05T01:00:00Z"));
        node.setWarningDurationMinutes(10);
        node.setOvertimeDurationMinutes(20);
        when(nodeDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(node));
        when(nodeDao.updateByIdAndVersion(node, 2)).thenReturn(1);
        when(instanceDao.findById("instance-1")).thenReturn(instance());

        WorkflowNodeOvertimeScanResult result = service.scan(Instant.parse("2026-06-05T01:21:00Z"));

        assertThat(result.warnedCount()).isZero();
        assertThat(result.overdueCount()).isEqualTo(1);
        assertThat(node.getOvertimeStatus()).isEqualTo(WorkflowOvertimeStatus.OVERDUE);
        assertThat(node.getWarnedAt()).isNull();
        assertThat(node.getOverdueAt()).isEqualTo(Instant.parse("2026-06-05T01:21:00Z"));
        ArgumentCaptor<WorkflowEvent> eventCaptor = ArgumentCaptor.forClass(WorkflowEvent.class);
        verify(eventDao).insert(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getEventType()).isEqualTo(WorkflowEventType.OVERTIME_OVERDUE);
        assertThat(eventCaptor.getValue().getPayloadText()).contains("\"currentStatus\":\"overdue\"");
    }

    @Test
    void shouldNotWriteDuplicateWhenNodeAlreadyMarkedOrCasFails() {
        WorkflowNodeInstance alreadyWarned = node();
        alreadyWarned.setActivatedAt(Instant.parse("2026-06-05T01:00:00Z"));
        alreadyWarned.setWarningDurationMinutes(10);
        alreadyWarned.setOvertimeStatus(WorkflowOvertimeStatus.WARNED);

        WorkflowNodeInstance casConflict = node();
        casConflict.setId("node-2");
        casConflict.setVersion(4);
        casConflict.setActivatedAt(Instant.parse("2026-06-05T01:00:00Z"));
        casConflict.setWarningDurationMinutes(10);
        when(nodeDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(alreadyWarned, casConflict));
        when(nodeDao.updateByIdAndVersion(casConflict, 4)).thenReturn(0);

        WorkflowNodeOvertimeScanResult result = service.scan(Instant.parse("2026-06-05T01:11:00Z"));

        assertThat(result.updatedCount()).isZero();
        verify(nodeDao).updateByIdAndVersion(casConflict, 4);
        verifyNoInteractions(instanceDao, eventDao);
    }

    @Test
    void shouldUpgradeWarnedNodeToOverdueOnce() {
        WorkflowNodeInstance warned = node();
        warned.setActivatedAt(Instant.parse("2026-06-05T01:00:00Z"));
        warned.setWarningDurationMinutes(10);
        warned.setOvertimeDurationMinutes(30);
        warned.setWarnedAt(Instant.parse("2026-06-05T01:10:00Z"));
        warned.setOvertimeStatus(WorkflowOvertimeStatus.WARNED);
        when(nodeDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(warned));
        when(nodeDao.updateByIdAndVersion(warned, 2)).thenReturn(1);
        when(instanceDao.findById("instance-1")).thenReturn(instance());

        WorkflowNodeOvertimeScanResult result = service.scan(Instant.parse("2026-06-05T01:31:00Z"));

        assertThat(result.warnedCount()).isZero();
        assertThat(result.overdueCount()).isEqualTo(1);
        assertThat(warned.getOvertimeStatus()).isEqualTo(WorkflowOvertimeStatus.OVERDUE);
        assertThat(warned.getWarnedAt()).isEqualTo(Instant.parse("2026-06-05T01:10:00Z"));
        assertThat(warned.getOverdueAt()).isEqualTo(Instant.parse("2026-06-05T01:31:00Z"));
        ArgumentCaptor<WorkflowEvent> eventCaptor = ArgumentCaptor.forClass(WorkflowEvent.class);
        verify(eventDao).insert(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getEventType()).isEqualTo(WorkflowEventType.OVERTIME_OVERDUE);
        assertThat(eventCaptor.getValue().getPayloadText())
                .contains("\"previousStatus\":\"warned\"")
                .contains("\"currentStatus\":\"overdue\"");
    }

    @Test
    void shouldNotWriteDuplicateWhenNodeAlreadyOverdue() {
        WorkflowNodeInstance overdue = node();
        overdue.setActivatedAt(Instant.parse("2026-06-05T01:00:00Z"));
        overdue.setWarningDurationMinutes(10);
        overdue.setOvertimeDurationMinutes(30);
        overdue.setOvertimeStatus(WorkflowOvertimeStatus.OVERDUE);
        when(nodeDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(overdue));

        WorkflowNodeOvertimeScanResult result = service.scan(Instant.parse("2026-06-05T01:31:00Z"));

        assertThat(result.updatedCount()).isZero();
        verify(nodeDao, never()).updateByIdAndVersion(any(), any());
        verifyNoInteractions(instanceDao, eventDao);
    }

    @Test
    void shouldIgnoreNonActiveApprovalNodesEvenIfDaoReturnsThem() {
        WorkflowNodeInstance taskNode = node();
        taskNode.setNodeType(WorkflowNodeType.TASK);
        taskNode.setActivatedAt(Instant.parse("2026-06-05T01:00:00Z"));
        taskNode.setWarningDurationMinutes(1);
        WorkflowNodeInstance waitingApproval = node();
        waitingApproval.setId("node-2");
        waitingApproval.setNodeStatus(WorkflowNodeStatus.WAITING);
        waitingApproval.setActivatedAt(Instant.parse("2026-06-05T01:00:00Z"));
        waitingApproval.setWarningDurationMinutes(1);
        when(nodeDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(taskNode, waitingApproval));

        WorkflowNodeOvertimeScanResult result = service.scan(Instant.parse("2026-06-05T01:02:00Z"));

        assertThat(result.scannedCount()).isEqualTo(2);
        assertThat(result.updatedCount()).isZero();
        verify(nodeDao, never()).updateByIdAndVersion(any(), any());
        verifyNoInteractions(instanceDao, eventDao);
    }

    private WorkflowNodeInstance node() {
        WorkflowNodeInstance node = new WorkflowNodeInstance();
        node.setId("node-1");
        node.setTenantId("tenant-1");
        node.setVersion(2);
        node.setInstanceId("instance-1");
        node.setNodeKey("approve");
        node.setNodeRunId("approve:1");
        node.setNodeType(WorkflowNodeType.APPROVAL);
        node.setNodeStatus(WorkflowNodeStatus.ACTIVE);
        node.setOvertimeStatus(WorkflowOvertimeStatus.NORMAL);
        return node;
    }

    private WorkflowInstance instance() {
        WorkflowInstance instance = new WorkflowInstance();
        instance.setId("instance-1");
        instance.setTenantId("tenant-1");
        instance.setModuleAlias("sales.contract");
        instance.setRecordId("record-1");
        return instance;
    }

    private void assertClause(CriteriaClause clause, String field, Object value) {
        assertThat(clause.getField()).isEqualTo(field);
        assertThat(clause.getOperator()).isEqualTo(CriteriaOperator.EQ);
        assertThat(clause.getValues()).containsExactly(value);
    }
}
