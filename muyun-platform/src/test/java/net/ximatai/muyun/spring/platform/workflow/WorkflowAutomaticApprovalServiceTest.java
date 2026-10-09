package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.ObjectProvider;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WorkflowAutomaticApprovalServiceTest {
    private final WorkflowTaskDao tasks = mock(WorkflowTaskDao.class);
    private final WorkflowNodeInstanceDao nodes = mock(WorkflowNodeInstanceDao.class);
    private final WorkflowTaskActionService actions = mock(WorkflowTaskActionService.class);
    private final ObjectProvider<WorkflowTaskActionService> provider = mock(ObjectProvider.class);
    private final WorkflowAutomaticApprovalService service = new WorkflowAutomaticApprovalService(tasks, nodes, provider);
    private final Instant now = Instant.parse("2026-10-07T12:00:00Z");

    @ParameterizedTest
    @CsvSource({"NORMAL,true,actor,actor,ACTIVE,true", "NORMAL,false,actor,actor,ACTIVE,false",
            "NORMAL,true,other,actor,ACTIVE,false", "NORMAL,true,actor,other,ACTIVE,false",
            "DELEGATED,true,actor,actor,ACTIVE,false", "TRANSFERRED,true,actor,actor,ACTIVE,false",
            "NORMAL,true,actor,actor,WAITING,false"})
    void onlyOptedInActiveApprovalWithUnchangedNormalAssigneeMayAdvance(WorkflowAssignmentKind assignment,
            boolean enabled, String original, String assignee, WorkflowNodeStatus status, boolean expected) {
        var task = task(); task.setAssignmentKind(assignment); task.setOriginalAssigneeId(original); task.setAssigneeId(assignee);
        var node = node(); node.setAutoApproveSameUser(enabled); node.setNodeStatus(status);
        configure(List.of(task), List.of(node));
        service.continueFor("instance", "actor", now);
        if (expected) {
            var request = org.mockito.ArgumentCaptor.forClass(WorkflowTaskActionRequest.class);
            verify(actions).approveAutomatically(request.capture());
            assertThat(request.getValue().taskId()).isEqualTo(task.getId());
            assertThat(request.getValue().operatorId()).isEqualTo("actor");
            assertThat(request.getValue().operatedAt()).isEqualTo(now);
            assertThat(request.getValue().reason()).isNotBlank();
        } else verifyNoInteractions(actions);
    }

    @Test void manualSelectorStopsAutomationEvenWhenItsApprovalIsOptedIn() {
        var branch = new WorkflowNodeInstance(); branch.setId("manual-node"); branch.setNodeKey("manual"); branch.setNodeType(WorkflowNodeType.BRANCH);
        branch.setRouteMode(WorkflowRouteMode.MANUAL); branch.setSelectorNodeKey("approval");
        configure(List.of(task()), List.of(node(), branch));
        service.continueFor("instance", "actor", now);
        verifyNoInteractions(actions);
    }

    @Test void optInDoesNotAutomaticallyCompleteBusinessOrNoticeTasksAndPreventsRecursiveReentry() {
        var business = task(); business.setId("business"); business.setTaskKind(WorkflowTaskKind.BUSINESS);
        var notice = task(); notice.setId("notice"); notice.setTaskKind(WorkflowTaskKind.NOTICE);
        var approval = task(); configure(List.of(business, notice, approval), List.of(node()));
        doAnswer(call -> { service.continueFor("instance", "actor", now); approval.setTaskStatus(WorkflowTaskStatus.DONE); return null; })
                .when(actions).approveAutomatically(any());
        service.continueFor("instance", "actor", now);
        verify(actions, times(1)).approveAutomatically(any());
        assertThat(business.getTaskStatus()).isEqualTo(WorkflowTaskStatus.TODO);
        assertThat(notice.getTaskStatus()).isEqualTo(WorkflowTaskStatus.TODO);
    }

    private void configure(List<WorkflowTask> queue, List<WorkflowNodeInstance> graph) {
        when(provider.getObject()).thenReturn(actions);
        when(nodes.query(any(Criteria.class), any(PageRequest.class))).thenReturn(graph);
        when(tasks.query(any(Criteria.class), any(PageRequest.class)))
                .thenAnswer(call -> queue.stream().filter(task -> task.getTaskStatus() == WorkflowTaskStatus.TODO).toList());
        doAnswer(call -> { var request = (WorkflowTaskActionRequest) call.getArgument(0);
            queue.stream().filter(task -> request.taskId().equals(task.getId())).forEach(task -> task.setTaskStatus(WorkflowTaskStatus.DONE));
            return null;
        }).when(actions).approveAutomatically(any());
    }
    private WorkflowTask task() {
        var task = new WorkflowTask(); task.setId("task"); task.setInstanceId("instance"); task.setNodeInstanceId("node");
        task.setTaskKind(WorkflowTaskKind.APPROVAL); task.setOriginalAssigneeId("actor"); task.setAssigneeId("actor"); return task;
    }
    private WorkflowNodeInstance node() {
        var node = new WorkflowNodeInstance(); node.setId("node"); node.setNodeKey("approval");
        node.setNodeType(WorkflowNodeType.APPROVAL); node.setNodeStatus(WorkflowNodeStatus.ACTIVE); node.setAutoApproveSameUser(true); return node;
    }
}
