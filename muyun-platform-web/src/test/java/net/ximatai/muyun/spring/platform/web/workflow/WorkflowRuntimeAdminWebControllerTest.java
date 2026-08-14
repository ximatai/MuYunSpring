package net.ximatai.muyun.spring.platform.web.workflow;

import net.ximatai.muyun.spring.web.CurrentUserWebFilter;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.workflow.WorkflowActionPolicyService;
import net.ximatai.muyun.spring.platform.workflow.WorkflowAdminFacade;
import net.ximatai.muyun.spring.platform.workflow.WorkflowAdminActiveTaskView;
import net.ximatai.muyun.spring.platform.workflow.WorkflowAdminInstanceQueryRequest;
import net.ximatai.muyun.spring.platform.workflow.WorkflowAdminInstanceView;
import net.ximatai.muyun.spring.platform.workflow.WorkflowApprovalStatus;
import net.ximatai.muyun.spring.platform.workflow.WorkflowEvent;
import net.ximatai.muyun.spring.platform.workflow.WorkflowEventType;
import net.ximatai.muyun.spring.platform.workflow.WorkflowHistoryEventView;
import net.ximatai.muyun.spring.platform.workflow.WorkflowHistoryInstance;
import net.ximatai.muyun.spring.platform.workflow.WorkflowInstance;
import net.ximatai.muyun.spring.platform.workflow.WorkflowInstanceActionRequest;
import net.ximatai.muyun.spring.platform.workflow.WorkflowInstanceActionResult;
import net.ximatai.muyun.spring.platform.workflow.WorkflowInstanceStatus;
import net.ximatai.muyun.spring.platform.workflow.WorkflowRuntimeRenderBundle;
import net.ximatai.muyun.spring.platform.workflow.WorkflowAssignmentKind;
import net.ximatai.muyun.spring.platform.workflow.WorkflowOvertimeStatus;
import net.ximatai.muyun.spring.platform.workflow.WorkflowTask;
import net.ximatai.muyun.spring.platform.workflow.WorkflowTaskActionRequest;
import net.ximatai.muyun.spring.platform.workflow.WorkflowTaskActionResult;
import net.ximatai.muyun.spring.platform.workflow.WorkflowTaskKind;
import net.ximatai.muyun.spring.platform.workflow.WorkflowTaskStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkflowRuntimeAdminWebControllerTest {
    private WorkflowAdminFacade adminFacade;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        adminFacade = mock(WorkflowAdminFacade.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new WorkflowRuntimeAdminWebController(adminFacade))
                .addFilters(new CurrentUserWebFilter(() -> Optional.of(
                        CurrentUser.tenantUser("user-1", "User", "tenant-a"))))
                .build();
    }

    @AfterEach
    void tearDown() {
        CurrentUserContext.clear();
        TenantContext.clear();
    }

    @Test
    void shouldExposeCurrentTodoTasks() throws Exception {
        WorkflowTask task = new WorkflowTask();
        task.setId("task-1");
        task.setTaskKind(WorkflowTaskKind.APPROVAL);
        task.setTaskStatus(WorkflowTaskStatus.TODO);
        when(adminFacade.currentTodoTasks("inst-1")).thenReturn(List.of(task));

        mvc.perform(get("/workflow/runtime/admin/instance/inst-1/todo-tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].id").value("task-1"));
    }

    @Test
    void shouldExposeCurrentActiveTaskViews() throws Exception {
        WorkflowAdminActiveTaskView view = new WorkflowAdminActiveTaskView("task-1", "inst-1", "node-1",
                "approve_1", "审批", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.TODO, "delegate-1", "代理人",
                Instant.parse("2026-06-05T01:00:00Z"), Instant.parse("2026-06-05T01:00:00Z"),
                WorkflowOvertimeStatus.WARNED, true, WorkflowAssignmentKind.DELEGATED, "principal-1",
                "原审批人", "principal-1", "原审批人", "delegate-1", "代理人", true, "delegation-1",
                "{\"delegationPolicyId\":\"delegation-1\"}", true, "approve_source", "operator-1",
                Instant.parse("2026-06-05T00:30:00Z"));
        when(adminFacade.currentTodoTaskViews("inst-1")).thenReturn(List.of(view));

        mvc.perform(get("/workflow/runtime/admin/instance/inst-1/active-tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].taskId").value("task-1"))
                .andExpect(jsonPath("$.records[0].nodeTitle").value("审批"))
                .andExpect(jsonPath("$.records[0].assigneeTitle").value("代理人"))
                .andExpect(jsonPath("$.records[0].canForceApprove").value(true))
                .andExpect(jsonPath("$.records[0].overtimeStatus").value("WARNED"))
                .andExpect(jsonPath("$.records[0].assignmentKind").value("DELEGATED"))
                .andExpect(jsonPath("$.records[0].originalAssigneeTitle").value("原审批人"))
                .andExpect(jsonPath("$.records[0].delegatedFromUserTitle").value("原审批人"))
                .andExpect(jsonPath("$.records[0].delegatedToUserTitle").value("代理人"))
                .andExpect(jsonPath("$.records[0].principalCanProcess").value(true))
                .andExpect(jsonPath("$.records[0].addedByAddSign").value(true))
                .andExpect(jsonPath("$.records[0].addSignSourceNodeKey").value("approve_source"))
                .andExpect(jsonPath("$.records[0].addSignOperatorId").value("operator-1"))
                .andExpect(jsonPath("$.records[0].addSignAt").value(1780619400.0));
    }

    @Test
    void shouldAuthorizeActiveTaskViewsThroughForceApproveAction() throws Exception {
        Method method = WorkflowRuntimeAdminWebController.class
                .getMethod("currentTodoTaskViews", String.class);

        CustomActionEndpoint endpoint = method.getAnnotation(CustomActionEndpoint.class);

        assertThat(endpoint.value()).isEqualTo(WorkflowActionPolicyService.MANAGEMENT_FORCE_APPROVE_ACTION);
        assertThat(endpoint.title()).isEqualTo("Force Handle");
    }

    @Test
    void shouldExposeAdminCurrentInstanceQueryAndDetails() throws Exception {
        WorkflowAdminInstanceView view = new WorkflowAdminInstanceView("inst-1", "sales.contract",
                "record-1", "definition-1", "version-1", 2, WorkflowInstanceStatus.RUNNING,
                WorkflowApprovalStatus.PROCESSING, "starter-1", "发起人",
                Instant.parse("2026-06-05T01:00:00Z"), List.of("approve_1"), List.of("审批"),
                List.of("task-1"), List.of("approver-1"), List.of("审批人"), WorkflowOvertimeStatus.WARNED,
                Instant.parse("2026-06-05T02:00:00Z"), Instant.parse("2026-06-05T02:30:00Z"));
        WorkflowTask task = new WorkflowTask();
        task.setId("task-1");
        task.setTaskKind(WorkflowTaskKind.APPROVAL);
        task.setTaskStatus(WorkflowTaskStatus.TODO);
        WorkflowEvent event = new WorkflowEvent();
        event.setId("event-1");
        event.setEventType(WorkflowEventType.TASK_COMPLETED);
        when(adminFacade.queryCurrentInstances(argThat(request ->
                        "sales.contract".equals(request.moduleAlias())
                                && "record-1".equals(request.recordId())
                                && "starter-1".equals(request.starterId())
                                && request.instanceStatus() == WorkflowInstanceStatus.RUNNING
                                && request.approvalStatus() == WorkflowApprovalStatus.PROCESSING
                                && "approver-1".equals(request.currentAssigneeId())
                                && request.overtimeStatus() == WorkflowOvertimeStatus.WARNED),
                any())).thenReturn(List.of(view));
        when(adminFacade.renderCurrentBundle("inst-1"))
                .thenReturn(new WorkflowRuntimeRenderBundle("RUNTIME", null, List.of(), List.of()));
        when(adminFacade.currentEvents("inst-1")).thenReturn(List.of(event));
        when(adminFacade.currentTasks("inst-1")).thenReturn(List.of(task));

        mvc.perform(post("/workflow/runtime/admin/instance/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"moduleAlias\":\"sales.contract\",\"recordId\":\"record-1\","
                                + "\"starterId\":\"starter-1\",\"instanceStatus\":\"RUNNING\","
                                + "\"approvalStatus\":\"PROCESSING\",\"currentAssigneeId\":\"approver-1\","
                                + "\"overtimeStatus\":\"WARNED\",\"page\":{\"pageNum\":1,\"pageSize\":20}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].instanceId").value("inst-1"))
                .andExpect(jsonPath("$.records[0].activeNodeKeys[0]").value("approve_1"))
                .andExpect(jsonPath("$.records[0].currentTaskIds[0]").value("task-1"))
                .andExpect(jsonPath("$.records[0].currentAssigneeIds[0]").value("approver-1"))
                .andExpect(jsonPath("$.records[0].overtimeStatus").value("WARNED"));
        mvc.perform(post("/workflow/runtime/admin/instance/inst-1/bundle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("RUNTIME"));
        mvc.perform(post("/workflow/runtime/admin/instance/inst-1/render")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("RUNTIME"));
        mvc.perform(post("/workflow/runtime/admin/instance/inst-1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].id").value("event-1"));
        mvc.perform(post("/workflow/runtime/admin/instance/inst-1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].id").value("task-1"));

        verify(adminFacade).queryCurrentInstances(any(WorkflowAdminInstanceQueryRequest.class), any());
        verify(adminFacade, times(2)).renderCurrentBundle("inst-1");
        verify(adminFacade).currentEvents("inst-1");
        verify(adminFacade).currentTasks("inst-1");
    }

    @Test
    void shouldExecuteManagementActionsWithCurrentUserFallback() throws Exception {
        WorkflowInstance instance = new WorkflowInstance();
        instance.setId("inst-1");
        WorkflowTask task = new WorkflowTask();
        task.setId("task-1");
        when(adminFacade.forceTerminate(argThat(request ->
                "inst-1".equals(request.instanceId())
                        && "admin-1".equals(request.operatorId())
                        && "force stop".equals(request.reason()))))
                .thenReturn(new WorkflowInstanceActionResult(instance, List.of(), List.of(), List.of(), null));
        when(adminFacade.reset(argThat(request ->
                "inst-1".equals(request.instanceId())
                        && "admin-2".equals(request.operatorId())
                        && "reset approval".equals(request.reason()))))
                .thenReturn(new WorkflowInstanceActionResult(instance, List.of(), List.of(), List.of(), null));
        when(adminFacade.forceApprove(argThat(request ->
                "task-1".equals(request.taskId())
                        && "user-1".equals(request.operatorId())
                        && "force agree".equals(request.reason())
                        && "leftRoute".equals(request.selectedRouteKey())
                        && "choose left".equals(request.selectedReason()))))
                .thenReturn(WorkflowTaskActionResult.of(task, null));

        mvc.perform(post("/workflow/runtime/admin/instance/inst-1/actions/forceTerminate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operatorId\":\"admin-1\",\"reason\":\"force stop\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instance.id").value("inst-1"));

        mvc.perform(post("/workflow/runtime/admin/instance/inst-1/actions/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operatorId\":\"admin-2\",\"reason\":\"reset approval\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instance.id").value("inst-1"));

        mvc.perform(post("/workflow/runtime/admin/task/task-1/actions/forceApprove")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"force agree\",\"selectedDirectLinkKey\":\"leftRoute\","
                                + "\"selectedReason\":\"choose left\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.id").value("task-1"));
    }

    @Test
    void shouldPreferSelectedRouteKeyWhenAdminForceApproveReceivesBothRouteKeys() throws Exception {
        WorkflowTask task = new WorkflowTask();
        task.setId("task-1");
        when(adminFacade.forceApprove(argThat(request ->
                "task-1".equals(request.taskId())
                        && "admin-1".equals(request.operatorId())
                        && "routeKey".equals(request.selectedRouteKey())
                        && "choose route".equals(request.selectedReason()))))
                .thenReturn(WorkflowTaskActionResult.of(task, null));

        mvc.perform(post("/workflow/runtime/admin/task/task-1/actions/forceApprove")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "operatorId": "admin-1",
                                  "selectedRouteKey": "routeKey",
                                  "selectedDirectLinkKey": "directLinkKey",
                                  "selectedReason": "choose route"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.id").value("task-1"));
    }

    @Test
    void shouldPassManualRouteSelectionsWhenAdminForceApprove() throws Exception {
        WorkflowTask task = new WorkflowTask();
        task.setId("task-structured");
        when(adminFacade.forceApprove(argThat(request ->
                "task-structured".equals(request.taskId())
                        && "admin-1".equals(request.operatorId())
                        && request.manualRouteSelections().size() == 2
                        && "branchA".equals(request.manualRouteSelections().get(0).branchNodeKey())
                        && "routeA1".equals(request.manualRouteSelections().get(0).routeKey())
                        && "choose A1".equals(request.manualRouteSelections().get(0).selectedReason())
                        && "branchB".equals(request.manualRouteSelections().get(1).branchNodeKey())
                        && "routeB2".equals(request.manualRouteSelections().get(1).routeKey()))))
                .thenReturn(WorkflowTaskActionResult.of(task, null));

        mvc.perform(post("/workflow/runtime/admin/task/task-structured/actions/forceApprove")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "operatorId": "admin-1",
                                  "manualRouteSelections": [
                                    {"branchNodeKey":"branchA","routeKey":"routeA1","selectedReason":"choose A1"},
                                    {"branchNodeKey":"branchB","routeKey":"routeB2","selectedReason":"choose B2"}
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.id").value("task-structured"));
    }

    @Test
    void shouldExposeAdminHistoryQueryDetailsAndDelete() throws Exception {
        WorkflowHistoryInstance history = new WorkflowHistoryInstance();
        history.setId("history-1");
        history.setModuleAlias("sales.contract");
        history.setRecordId("record-1");
        WorkflowEvent event = new WorkflowEvent();
        event.setId("event-1");
        event.setEventType(WorkflowEventType.INSTANCE_TERMINATED);
        when(adminFacade.queryHistory(eq("sales.contract"), eq("record-1"), eq("starter-1"), any()))
                .thenReturn(List.of(history));
        when(adminFacade.renderHistoryBundle("history-1"))
                .thenReturn(new WorkflowRuntimeRenderBundle("HISTORY", null, List.of(), List.of()));
        when(adminFacade.historyEvents("history-1")).thenReturn(List.of(event));
        when(adminFacade.historyEventViews("history-1")).thenReturn(List.of(new WorkflowHistoryEventView(
                WorkflowHistoryEventView.ORIGIN_TYPE_DEFINITION, false, null, null,
                "event-1", "instance-1", null, null, WorkflowEventType.INSTANCE_TERMINATED, "forceTerminate",
                "admin-1", "管理员", "admin-1", "管理员", false, WorkflowAssignmentKind.NORMAL, null, null,
                null, null, null, null, null, null, null, false, false, null, null, null)));
        when(adminFacade.deleteHistory("history-1")).thenReturn(1);

        mvc.perform(post("/workflow/runtime/admin/history/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"moduleAlias\":\"sales.contract\",\"recordId\":\"record-1\","
                                + "\"startedBy\":\"starter-1\","
                                + "\"page\":{\"pageNum\":2,\"pageSize\":10}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].id").value("history-1"));
        mvc.perform(post("/workflow/runtime/admin/history/history-1/bundle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("HISTORY"));
        mvc.perform(post("/workflow/runtime/admin/history/history-1/render")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("HISTORY"));
        mvc.perform(post("/workflow/runtime/admin/history/history-1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].id").value("event-1"));
        mvc.perform(post("/workflow/runtime/admin/history/history-1/events/view")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].actualProcessUserId").value("admin-1"));
        mvc.perform(post("/workflow/runtime/admin/history/history-1/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(1));

        verify(adminFacade).queryHistory(eq("sales.contract"), eq("record-1"), eq("starter-1"), any());
        verify(adminFacade).deleteHistory("history-1");
    }
}
