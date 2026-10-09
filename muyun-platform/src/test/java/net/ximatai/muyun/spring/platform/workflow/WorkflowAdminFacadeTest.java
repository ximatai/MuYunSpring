package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.database.core.orm.PageRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowAdminFacadeTest {
    @org.junit.jupiter.api.BeforeEach
    void installWorkflowMutationHost() { WorkflowTestMutationHost.install(); }
    @org.junit.jupiter.api.AfterEach
    void resetWorkflowMutationHost() { WorkflowTestMutationHost.reset(); }

    private final WorkflowAdminService adminService = mock(WorkflowAdminService.class);
    private final WorkflowAdminFacade facade = new WorkflowAdminFacade(adminService);

    @Test
    void shouldDelegateAdminContracts() {
        WorkflowTask task = new WorkflowTask();
        task.setId("task-1");
        WorkflowAdminActiveTaskView view = new WorkflowAdminActiveTaskView("task-1", "instance-1", "node-1",
                "approve_1", "审批", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO, "approver-1",
                "审批人", null, null, WorkflowOvertimeStatus.NORMAL, true, WorkflowAssignmentKind.NORMAL,
                "approver-1", "审批人", null, null, null, null, false, null, null, null, null, null, null);
        when(adminService.currentTodoTasks("instance-1")).thenReturn(List.of(task));
        when(adminService.currentTodoTaskViews("instance-1")).thenReturn(List.of(view));
        WorkflowAdminInstanceQueryRequest queryRequest = WorkflowAdminInstanceQueryRequest.empty();
        WorkflowAdminInstanceView instanceView = new WorkflowAdminInstanceView("instance-1", "sales.contract",
                "record-1", "definition-1", "version-1", 1, WorkflowInstanceStatus.RUNNING,
                WorkflowApprovalStatus.PROCESSING, "starter-1", "发起人", null, List.of("approve_1"),
                List.of("审批"), List.of("task-1"), List.of("approver-1"), List.of("审批人"),
                WorkflowOvertimeStatus.NORMAL, null, null);
        PageRequest pageRequest = PageRequest.of(1, 20);
        when(adminService.queryCurrentInstances(queryRequest, pageRequest))
                .thenReturn(List.of(instanceView));
        WorkflowInstanceActionRequest instanceRequest = WorkflowInstanceActionRequest.terminate(
                "instance-1", "admin-1", "force stop");
        WorkflowTaskActionRequest taskRequest = WorkflowTaskActionRequest.complete(
                "task-1", "admin-1", "force approve");
        WorkflowRuntimeRenderBundle currentBundle = new WorkflowRuntimeRenderBundle("RUNTIME", null, List.of(), List.of());
        WorkflowEvent event = new WorkflowEvent();
        event.setId("event-1");
        WorkflowRuntimeRenderBundle bundle = new WorkflowRuntimeRenderBundle("HISTORY", null, List.of(), List.of());
        when(adminService.renderCurrentBundle("instance-1")).thenReturn(currentBundle);
        when(adminService.renderInstanceBundle("instance-1")).thenReturn(currentBundle);
        when(adminService.currentEvents("instance-1")).thenReturn(List.of(event));
        when(adminService.currentTasks("instance-1")).thenReturn(List.of(task));
        when(adminService.queryHistory("sales.contract", "record-1", pageRequest)).thenReturn(List.of());
        when(adminService.renderHistoryBundle("history-1")).thenReturn(bundle);
        when(adminService.historyEvents("history-1")).thenReturn(List.of());
        when(adminService.historyEventViews("history-1")).thenReturn(List.of());
        when(adminService.deleteHistory("history-1")).thenReturn(1);

        assertThat(facade.currentTodoTasks("instance-1")).containsExactly(task);
        assertThat(facade.currentTodoTaskViews("instance-1")).containsExactly(view);
        assertThat(facade.queryCurrentInstances(queryRequest, pageRequest)).containsExactly(instanceView);
        facade.reset(instanceRequest);
        facade.forceTerminate(instanceRequest);
        facade.forceApprove(taskRequest);
        assertThat(facade.renderCurrentBundle("instance-1")).isEqualTo(currentBundle);
        assertThat(facade.renderInstanceBundle("instance-1")).isEqualTo(currentBundle);
        assertThat(facade.currentEvents("instance-1")).containsExactly(event);
        assertThat(facade.currentTasks("instance-1")).containsExactly(task);
        assertThat(facade.queryHistory("sales.contract", "record-1", pageRequest)).isEmpty();
        assertThat(facade.renderHistoryBundle("history-1")).isEqualTo(bundle);
        assertThat(facade.historyEvents("history-1")).isEmpty();
        assertThat(facade.historyEventViews("history-1")).isEmpty();
        assertThat(facade.deleteHistory("history-1")).isEqualTo(1);

        verify(adminService).reset(instanceRequest);
        verify(adminService).forceTerminate(instanceRequest);
        verify(adminService).forceApprove(taskRequest);
        verify(adminService).queryCurrentInstances(queryRequest, pageRequest);
        verify(adminService).renderCurrentBundle("instance-1");
        verify(adminService).renderInstanceBundle("instance-1");
        verify(adminService).currentEvents("instance-1");
        verify(adminService).currentTasks("instance-1");
        verify(adminService).queryHistory("sales.contract", "record-1", pageRequest);
        verify(adminService).renderHistoryBundle("history-1");
        verify(adminService).historyEvents("history-1");
        verify(adminService).historyEventViews("history-1");
        verify(adminService).deleteHistory("history-1");
    }
}
