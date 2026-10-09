package net.ximatai.muyun.spring.platform.web.workflow;

import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.web.CurrentUserWebFilter;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.workflow.WorkflowApprovalStatus;
import net.ximatai.muyun.spring.platform.workflow.WorkflowInstance;
import net.ximatai.muyun.spring.platform.workflow.WorkflowInstanceActionFacade;
import net.ximatai.muyun.spring.platform.workflow.WorkflowInstanceActionRequest;
import net.ximatai.muyun.spring.platform.workflow.WorkflowInstanceActionResult;
import net.ximatai.muyun.spring.platform.workflow.WorkflowInstanceStatus;
import net.ximatai.muyun.spring.platform.workflow.WorkflowModuleTaskCompletionPolicy;
import net.ximatai.muyun.spring.platform.workflow.WorkflowModuleTaskContext;
import net.ximatai.muyun.spring.platform.workflow.WorkflowModuleTaskContinueResult;
import net.ximatai.muyun.spring.platform.workflow.WorkflowModuleTaskEvaluation;
import net.ximatai.muyun.spring.platform.workflow.WorkflowModuleTaskProcessBundle;
import net.ximatai.muyun.spring.platform.workflow.WorkflowModuleTaskRuntimeService;
import net.ximatai.muyun.spring.platform.workflow.WorkflowNoticeReadStatus;
import net.ximatai.muyun.spring.platform.workflow.WorkflowNodeInstance;
import net.ximatai.muyun.spring.platform.workflow.WorkflowNodeType;
import net.ximatai.muyun.spring.platform.workflow.WorkflowManualBranchCandidatePrecheckView;
import net.ximatai.muyun.spring.platform.workflow.WorkflowManualBranchCandidateView;
import net.ximatai.muyun.spring.platform.workflow.WorkflowManualRouteSelection;
import net.ximatai.muyun.spring.platform.workflow.WorkflowRejectResubmitMode;
import net.ximatai.muyun.spring.platform.workflow.WorkflowRouteMode;
import net.ximatai.muyun.spring.platform.workflow.WorkflowRouteStatus;
import net.ximatai.muyun.spring.platform.workflow.WorkflowRuntimeAddSignExplanationView;
import net.ximatai.muyun.spring.platform.workflow.WorkflowRuntimeReadFacade;
import net.ximatai.muyun.spring.platform.workflow.WorkflowRuntimeRenderBundle;
import net.ximatai.muyun.spring.platform.workflow.WorkflowSubmitDraft;
import net.ximatai.muyun.spring.platform.workflow.WorkflowSubmitFacade;
import net.ximatai.muyun.spring.platform.workflow.WorkflowSubmitPreviewView;
import net.ximatai.muyun.spring.platform.workflow.WorkflowSubmitReadFacade;
import net.ximatai.muyun.spring.platform.workflow.WorkflowSubmitRequest;
import net.ximatai.muyun.spring.platform.workflow.WorkflowSubmitResult;
import net.ximatai.muyun.spring.platform.workflow.WorkflowSubmitStatusView;
import net.ximatai.muyun.spring.platform.workflow.WorkflowActivationResult;
import net.ximatai.muyun.spring.platform.workflow.WorkflowTask;
import net.ximatai.muyun.spring.platform.workflow.WorkflowTaskActionFacade;
import net.ximatai.muyun.spring.platform.workflow.WorkflowTaskActionRequest;
import net.ximatai.muyun.spring.platform.workflow.WorkflowTaskActionResult;
import net.ximatai.muyun.spring.platform.workflow.WorkflowTaskAvailableAction;
import net.ximatai.muyun.spring.platform.workflow.WorkflowTaskDefinition;
import net.ximatai.muyun.spring.platform.workflow.WorkflowTaskKind;
import net.ximatai.muyun.spring.platform.workflow.WorkflowTaskStatus;
import net.ximatai.muyun.spring.platform.workflow.WorkflowWorkbenchQueryRequest;
import net.ximatai.muyun.spring.platform.workflow.WorkflowWorkbenchCard;
import net.ximatai.muyun.spring.platform.workflow.WorkflowWorkbenchStatItem;
import net.ximatai.muyun.spring.platform.workflow.WorkflowWorkbenchStats;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkflowRuntimeWebControllerTest {
    private WorkflowRuntimeReadFacade runtimeReadFacade;
    private WorkflowTaskActionFacade taskActionFacade;
    private WorkflowInstanceActionFacade instanceActionFacade;
    private WorkflowModuleTaskRuntimeService moduleTaskRuntimeService;
    private WorkflowSubmitFacade submitFacade;
    private WorkflowSubmitReadFacade submitReadFacade;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        runtimeReadFacade = mock(WorkflowRuntimeReadFacade.class);
        taskActionFacade = mock(WorkflowTaskActionFacade.class);
        instanceActionFacade = mock(WorkflowInstanceActionFacade.class);
        moduleTaskRuntimeService = mock(WorkflowModuleTaskRuntimeService.class);
        submitFacade = mock(WorkflowSubmitFacade.class);
        submitReadFacade = mock(WorkflowSubmitReadFacade.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new WorkflowRuntimeWebController(runtimeReadFacade, taskActionFacade,
                        instanceActionFacade, moduleTaskRuntimeService, submitFacade, submitReadFacade))
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
    void submissionCannotReplaceAuthenticatedOrganizationOrOperator() throws Exception {
        mvc.perform(post("/workflow/runtime/record/test.order/record-1/submit/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"operatorId\":\"forged-user\",\"authOrgId\":\"forged-organization\"}"))
                .andExpect(status().isOk());
        verify(submitReadFacade).status(argThat(request -> request.authOrgId() == null
                && "user-1".equals(request.operatorId())));
    }

    @Test
    void taskManualBranchPlanningUsesTheAuthenticatedOperatorAndPreservesPartialSelections() throws Exception {
        var selections = List.of(new WorkflowManualRouteSelection("first", "choice", null));
        when(runtimeReadFacade.manualBranchCandidates("inst-1", "task-1", selections, "user-1"))
                .thenReturn(List.of(new WorkflowManualBranchCandidateView("next", "后续分支", WorkflowRouteMode.MANUAL,
                        "approve", false, List.of(), true)));
        mvc.perform(post("/workflow/runtime/instance/inst-1/manual-branches")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"taskId\":\"task-1\",\"manualRouteSelections\":[{\"branchNodeKey\":\"first\",\"routeKey\":\"choice\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].branchTitle").value("后续分支"))
                .andExpect(jsonPath("$.records[0].selectionPending").value(true));
        verify(runtimeReadFacade).manualBranchCandidates("inst-1", "task-1", selections, "user-1");
    }

    @Test
    void submissionPlanningRetainsAlreadySelectedBranchesForTheNextFrontier() throws Exception {
        mvc.perform(post("/workflow/runtime/record/test.order/record-1/submit/manual-branches")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"manualRouteSelections\":[{\"branchNodeKey\":\"first\",\"routeKey\":\"choice\"}]}"))
                .andExpect(status().isOk());
        verify(submitReadFacade).manualBranches(argThat(request -> request.manualRouteSelections().equals(
                List.of(new WorkflowManualRouteSelection("first", "choice", null))) && "user-1".equals(request.operatorId())));
    }

    @Test
    void shouldExposeInstanceRenderBundle() throws Exception {
        WorkflowInstance instance = instance("inst-1");
        instance.setSemanticJson("{\"nodes\":[\"review\"]}");
        instance.setLayoutJson("{\"zoom\":1}");
        WorkflowNodeInstance node = new WorkflowNodeInstance();
        node.setId("node-1");
        node.setInstanceId("inst-1");
        node.setNodeKey("review");
        when(runtimeReadFacade.renderBundle("inst-1"))
                .thenReturn(new WorkflowRuntimeRenderBundle("RUNTIME", instance, List.of(node), List.of()));

        mvc.perform(get("/workflow/runtime/instance/inst-1/bundle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("RUNTIME"))
                .andExpect(jsonPath("$.instance.id").value("inst-1"))
                .andExpect(jsonPath("$.semanticJson").value("{\"nodes\":[\"review\"]}"))
                .andExpect(jsonPath("$.layoutJson").value("{\"zoom\":1}"))
                .andExpect(jsonPath("$.nodes[0].nodeKey").value("review"));
    }

    @Test
    void shouldExposeManualBranchCandidates() throws Exception {
        when(runtimeReadFacade.manualBranchCandidates("inst-1")).thenReturn(List.of(
                new WorkflowManualBranchCandidateView("manualBranch", WorkflowRouteMode.MANUAL, "approve",
                        Boolean.TRUE, List.of(
                        new WorkflowManualBranchCandidateView.Candidate("route-1", "leftRoute", "leftTask",
                                WorkflowNodeType.TASK, WorkflowRouteStatus.CANDIDATE, Boolean.FALSE)
                ))));

        mvc.perform(get("/workflow/runtime/instance/inst-1/manual-branches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].branchNodeKey").value("manualBranch"))
                .andExpect(jsonPath("$.records[0].routeMode").value("MANUAL"))
                .andExpect(jsonPath("$.records[0].selectorNodeKey").value("approve"))
                .andExpect(jsonPath("$.records[0].requireManualSelectionReason").value(true))
                .andExpect(jsonPath("$.records[0].candidates[0].routeId").value("route-1"))
                .andExpect(jsonPath("$.records[0].candidates[0].routeKey").value("leftRoute"))
                .andExpect(jsonPath("$.records[0].candidates[0].targetNodeKey").value("leftTask"))
                .andExpect(jsonPath("$.records[0].candidates[0].targetNodeType").value("TASK"))
                .andExpect(jsonPath("$.records[0].candidates[0].routeStatus").value("CANDIDATE"))
                .andExpect(jsonPath("$.records[0].candidates[0].defaultRoute").value(false));

        verify(runtimeReadFacade).manualBranchCandidates("inst-1");
    }

    @Test
    void shouldExposeManualBranchCandidatePrechecksWithCurrentUser() throws Exception {
        when(runtimeReadFacade.manualBranchCandidatePrechecks("inst-1", "user-1")).thenReturn(List.of(
                new WorkflowManualBranchCandidatePrecheckView("manualBranch", WorkflowRouteMode.MANUAL, "START",
                        Boolean.TRUE, "user-1", "user-1", Boolean.TRUE, null, List.of(
                        new WorkflowManualBranchCandidatePrecheckView.Candidate("route-1", "leftRoute", "leftTask",
                                WorkflowNodeType.TASK, WorkflowRouteStatus.CANDIDATE, Boolean.FALSE, Boolean.TRUE,
                                null)
                ))));
        mvc.perform(get("/workflow/runtime/instance/inst-1/manual-branch-candidate-prechecks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].branchNodeKey").value("manualBranch"))
                .andExpect(jsonPath("$.records[0].selectorNodeKey").value("START"))
                .andExpect(jsonPath("$.records[0].selectorResolvedUserId").value("user-1"))
                .andExpect(jsonPath("$.records[0].operatorId").value("user-1"))
                .andExpect(jsonPath("$.records[0].selectable").value(true))
                .andExpect(jsonPath("$.records[0].candidates[0].routeKey").value("leftRoute"))
                .andExpect(jsonPath("$.records[0].candidates[0].selectable").value(true));

        mvc.perform(get("/workflow/runtime/instance/inst-1/manual-branch-candidate-prechecks")
                        .param("operatorId", "operator-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].operatorId").value("user-1"))
                .andExpect(jsonPath("$.records[0].selectable").value(true))
                .andExpect(jsonPath("$.records[0].candidates[0].selectable").value(true));

        verify(runtimeReadFacade, org.mockito.Mockito.times(2)).manualBranchCandidatePrechecks("inst-1", "user-1");
    }

    @Test
    void shouldExposeInstanceTasksEventsAndAvailableActions() throws Exception {
        WorkflowTask task = new WorkflowTask();
        task.setId("task-1");
        task.setTaskKind(WorkflowTaskKind.APPROVAL);
        task.setTaskStatus(WorkflowTaskStatus.TODO);
        when(runtimeReadFacade.instanceTasks("inst-1")).thenReturn(List.of(task));
        when(runtimeReadFacade.instanceEvents("inst-1")).thenReturn(List.of());
        when(runtimeReadFacade.instanceAvailableActions("inst-1", "user-1"))
                .thenReturn(List.of(WorkflowTaskAvailableAction.of("complete", "通过")));

        mvc.perform(get("/workflow/runtime/instance/inst-1/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].id").value("task-1"));
        mvc.perform(get("/workflow/runtime/instance/inst-1/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records").isArray());
        mvc.perform(post("/workflow/runtime/instance/inst-1/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operatorId\":\"operator-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].actionCode").value("complete"));
    }

    @Test
    void shouldExposeRuntimeAddSignExplanations() throws Exception {
        when(runtimeReadFacade.addSignExplanations("inst-1")).thenReturn(List.of(
                new WorkflowRuntimeAddSignExplanationView("ADD_SIGN", "NODE", Boolean.FALSE,
                        "node-add", "add-1", WorkflowNodeType.APPROVAL, null,
                        null, null, null, null, null,
                        "approve", "审批节点", "operator-1",
                        Instant.parse("2026-06-05T01:00:00Z")),
                new WorkflowRuntimeAddSignExplanationView("ADD_SIGN", "ROUTE", Boolean.TRUE,
                        null, null, null, null,
                        "route-add", "entry-add", "approve", "add-1", WorkflowRouteStatus.CANDIDATE,
                        "approve", "审批节点", "operator-1",
                        Instant.parse("2026-06-05T01:00:00Z"))
        ));

        mvc.perform(get("/workflow/runtime/instance/inst-1/add-sign-explanations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].originType").value("ADD_SIGN"))
                .andExpect(jsonPath("$.records[0].dimension").value("NODE"))
                .andExpect(jsonPath("$.records[0].isAddSignRoute").value(false))
                .andExpect(jsonPath("$.records[0].nodeInstanceId").value("node-add"))
                .andExpect(jsonPath("$.records[0].nodeKey").value("add-1"))
                .andExpect(jsonPath("$.records[0].nodeType").value("APPROVAL"))
                .andExpect(jsonPath("$.records[0].addSignSourceNodeKey").value("approve"))
                .andExpect(jsonPath("$.records[0].addSignSourceNodeName").value("审批节点"))
                .andExpect(jsonPath("$.records[0].addSignOperatorId").value("operator-1"))
                .andExpect(jsonPath("$.records[0].addSignAt").exists())
                .andExpect(jsonPath("$.records[1].dimension").value("ROUTE"))
                .andExpect(jsonPath("$.records[1].isAddSignRoute").value(true))
                .andExpect(jsonPath("$.records[1].routeId").value("route-add"))
                .andExpect(jsonPath("$.records[1].routeKey").value("entry-add"))
                .andExpect(jsonPath("$.records[1].routeSourceNodeKey").value("approve"))
                .andExpect(jsonPath("$.records[1].routeTargetNodeKey").value("add-1"))
                .andExpect(jsonPath("$.records[1].routeStatus").value("CANDIDATE"));

        verify(runtimeReadFacade).addSignExplanations("inst-1");
    }

    @Test
    void shouldExposeRecordSubmitStatusWithOptionalOperator() throws Exception {
        when(submitReadFacade.status(argThat(request -> "sales.contract".equals(request.moduleAlias())
                && "record-1".equals(request.recordId())
                && "user-1".equals(request.operatorId()))))
                .thenReturn(WorkflowSubmitStatusView.unsubmitted("sales.contract", "record-1", null));

        mvc.perform(post("/workflow/runtime/record/sales.contract/record-1/submit/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operatorId\":\"operator-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayStatus").value("UNSUBMITTED"));
    }

    @Test
    void shouldExposeRecordSubmitPreviewWithOperatorFallback() throws Exception {
        WorkflowInstance instance = instance("preview-1");
        when(submitReadFacade.preview(argThat(request -> "sales.contract".equals(request.moduleAlias())
                && "record-1".equals(request.recordId())
                && "user-1".equals(request.operatorId())
                && "leftRoute".equals(request.selectedRouteKey()))))
                .thenReturn(new WorkflowSubmitPreviewView("SUBMIT_PREVIEW", null, instance, List.of(), List.of(),
                        List.of(), List.of(), null));

        mvc.perform(post("/workflow/runtime/record/sales.contract/record-1/submit/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selectedDirectLinkKey\":\"leftRoute\",\"selectedReason\":\"choose left\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("SUBMIT_PREVIEW"))
                .andExpect(jsonPath("$.instance.id").value("preview-1"));
    }

    @Test
    void shouldExposeStaticSubmitApprovalAction() throws Exception {
        WorkflowSubmitResult submitResult = new WorkflowSubmitResult(
                new WorkflowSubmitDraft(instance("inst-1"), List.of(), List.of(), List.of(), List.of(),
                        new WorkflowActivationResult(List.of(), List.of(), List.of(), List.of(), List.of(),
                                List.of(), false)),
                true);
        when(submitFacade.submit(argThat(request -> "sales.contract".equals(request.moduleAlias())
                && "record-1".equals(request.recordId())
                && "user-1".equals(request.operatorId())
                && "branchA".equals(request.selectedRouteKey()))))
                .thenReturn(submitResult);

        mvc.perform(post("/workflow/runtime/record/sales.contract/record-1/actions/submitApproval")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operatorId\":\"operator-1\",\"selectedRouteKey\":\"branchA\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draft.instance.id").value("inst-1"))
                .andExpect(jsonPath("$.approvalSummaryWritten").value(true));

        ArgumentCaptor<WorkflowSubmitRequest> captor = ArgumentCaptor.forClass(WorkflowSubmitRequest.class);
        verify(submitFacade).submit(captor.capture());
        assertThat(captor.getValue().approvalRequired()).isTrue();
    }

    @Test
    void shouldQueryWorkbenchCardsWithNormalizedPageAndCurrentUserFallback() throws Exception {
        WorkflowWorkbenchCard card = new WorkflowWorkbenchCard("TODO", "inst-1", "crm.contract", "record-1",
                "def-1", "ver-1", WorkflowInstanceStatus.RUNNING, WorkflowApprovalStatus.PROCESSING, "task-1",
                WorkflowTaskKind.BUSINESS, WorkflowTaskStatus.TODO, "visit", "回访", "visit", List.of("user-1"),
                List.of("处理人"), null, null, null, null, null, null, null, null, "user-1", "处理人",
                null, null, null, null, Boolean.TRUE, null, null, null, Boolean.TRUE, "approve", "operator-1", "加签人",
                Instant.parse("2026-06-05T00:30:00Z"));
        ArgumentCaptor<PageRequest> pageCaptor = ArgumentCaptor.forClass(PageRequest.class);
        ArgumentCaptor<WorkflowWorkbenchQueryRequest> queryCaptor =
                ArgumentCaptor.forClass(WorkflowWorkbenchQueryRequest.class);
        when(runtimeReadFacade.todoCards(eq("user-1"), pageCaptor.capture(), queryCaptor.capture()))
                .thenReturn(List.of(card));

        mvc.perform(post("/workflow/runtime/workbench/todo/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "page": {"pageNum": 2, "pageSize": 30},
                                  "moduleAlias": "crm.contract",
                                  "nodeKey": "visit",
                                  "submitterUserId": "starter-1",
                                  "addedByAddSign": true,
                                  "addSignSourceNodeKey": "approve",
                                  "sorts": [
                                    {"field": "receivedAt", "direction": "ASC"}
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].boardType").value("TODO"))
                .andExpect(jsonPath("$.records[0].taskId").value("task-1"))
                .andExpect(jsonPath("$.records[0].addedByAddSign").value(true))
                .andExpect(jsonPath("$.records[0].addSignSourceNodeKey").value("approve"))
                .andExpect(jsonPath("$.records[0].addSignOperatorId").value("operator-1"))
                .andExpect(jsonPath("$.records[0].addSignAt").exists());

        assertThat(pageCaptor.getValue().getOffset()).isEqualTo(30);
        assertThat(pageCaptor.getValue().getLimit()).isEqualTo(30);
        assertThat(queryCaptor.getValue().moduleAlias()).isEqualTo("crm.contract");
        assertThat(queryCaptor.getValue().nodeKey()).isEqualTo("visit");
        assertThat(queryCaptor.getValue().submitterUserId()).isEqualTo("starter-1");
        assertThat(queryCaptor.getValue().addedByAddSign()).isTrue();
        assertThat(queryCaptor.getValue().addSignSourceNodeKey()).isEqualTo("approve");
        assertThat(queryCaptor.getValue().sorts().getFirst().field()).isEqualTo("receivedAt");
    }

    @Test
    void shouldExposeDoneNoticeAndTrackingWorkbenchBoards() throws Exception {
        when(runtimeReadFacade.doneCards(eq("user-1"), org.mockito.ArgumentMatchers.any(PageRequest.class),
                org.mockito.ArgumentMatchers.any(WorkflowWorkbenchQueryRequest.class)))
                .thenReturn(List.of());
        when(runtimeReadFacade.noticeCards(eq("user-1"), org.mockito.ArgumentMatchers.any(PageRequest.class),
                org.mockito.ArgumentMatchers.any(WorkflowWorkbenchQueryRequest.class)))
                .thenReturn(List.of());
        when(runtimeReadFacade.delegationCards(eq("user-1"), org.mockito.ArgumentMatchers.any(PageRequest.class),
                org.mockito.ArgumentMatchers.any(WorkflowWorkbenchQueryRequest.class)))
                .thenReturn(List.of());
        ArgumentCaptor<WorkflowWorkbenchQueryRequest> trackingQueryCaptor =
                ArgumentCaptor.forClass(WorkflowWorkbenchQueryRequest.class);
        when(runtimeReadFacade.trackingCards(eq("user-1"), org.mockito.ArgumentMatchers.any(PageRequest.class),
                trackingQueryCaptor.capture()))
                .thenReturn(List.of());

        String request = "{\"operatorId\":\"operator-1\"}";
        mvc.perform(post("/workflow/runtime/workbench/done/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records").isArray());
        mvc.perform(post("/workflow/runtime/workbench/notice/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records").isArray());
        mvc.perform(post("/workflow/runtime/workbench/tracking/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operatorId\":\"operator-1\",\"instanceStatus\":\"RUNNING\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records").isArray());
        mvc.perform(post("/workflow/runtime/workbench/delegation/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records").isArray());

        assertThat(trackingQueryCaptor.getValue().instanceStatus()).isEqualTo(WorkflowInstanceStatus.RUNNING);
    }

    @Test
    void shouldPassNoticeReadStatusAndExposeWorkbenchStats() throws Exception {
        ArgumentCaptor<WorkflowWorkbenchQueryRequest> noticeQueryCaptor =
                ArgumentCaptor.forClass(WorkflowWorkbenchQueryRequest.class);
        ArgumentCaptor<WorkflowWorkbenchQueryRequest> statsQueryCaptor =
                ArgumentCaptor.forClass(WorkflowWorkbenchQueryRequest.class);
        when(runtimeReadFacade.noticeCards(eq("user-1"), org.mockito.ArgumentMatchers.any(PageRequest.class),
                noticeQueryCaptor.capture()))
                .thenReturn(List.of());
        when(runtimeReadFacade.workbenchStats(eq("notice"), eq("user-1"), statsQueryCaptor.capture()))
                .thenReturn(new WorkflowWorkbenchStats("NOTICE", List.of(
                        new WorkflowWorkbenchStatItem("ALL", "全部", 2),
                        new WorkflowWorkbenchStatItem("UNREAD", "未读", 1),
                        new WorkflowWorkbenchStatItem("READ", "已读", 1)
                )));

        mvc.perform(post("/workflow/runtime/workbench/notice/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operatorId\":\"operator-1\",\"readStatus\":\"READ\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records").isArray());

        mvc.perform(post("/workflow/runtime/workbench/notice/stats")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operatorId\":\"operator-1\",\"readStatus\":\"READ\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.boardType").value("NOTICE"))
                .andExpect(jsonPath("$.items[1].code").value("UNREAD"))
                .andExpect(jsonPath("$.items[1].count").value(1));

        assertThat(noticeQueryCaptor.getValue().readStatus()).isEqualTo(WorkflowNoticeReadStatus.READ);
        assertThat(statsQueryCaptor.getValue().readStatus()).isEqualTo(WorkflowNoticeReadStatus.READ);
    }

    @Test
    void shouldExecuteTaskActionsThroughTaskActionFacade() throws Exception {
        WorkflowTask task = new WorkflowTask();
        task.setId("task-1");
        when(taskActionFacade.execute(eq("approve"), argThat(request ->
                "task-1".equals(request.taskId())
                        && "user-1".equals(request.operatorId())
                        && "leftRoute".equals(request.selectedRouteKey())
                        && "choose left".equals(request.selectedReason()))))
                .thenReturn(WorkflowTaskActionResult.of(task, null));
        when(taskActionFacade.execute(eq("reject"), argThat(request ->
                "task-1".equals(request.taskId())
                        && "user-1".equals(request.operatorId())
                        && request.rejectResubmitMode() == WorkflowRejectResubmitMode.RETURN_TO_ME
                        && "not ok".equals(request.reason()))))
                .thenReturn(WorkflowTaskActionResult.of(task, null));
        when(taskActionFacade.execute(eq("transfer"), argThat(request ->
                "task-1".equals(request.taskId())
                        && "user-1".equals(request.operatorId())
                        && "user-2".equals(request.targetAssigneeId())
                        && "handoff".equals(request.reason()))))
                .thenReturn(WorkflowTaskActionResult.transferred(task, new WorkflowTask(), null));
        when(taskActionFacade.execute(eq("addSign"), argThat(request ->
                "task-1".equals(request.taskId())
                        && "user-1".equals(request.operatorId())
                        && request.targetAssigneeId() == null
                        && request.addSignMode() == null
                        && request.addSignSegment() != null
                        && request.addSignSegment().nodeDefinitions().size() == 1
                        && "add-1".equals(request.addSignSegment().nodeDefinitions().getFirst().getNodeKey())
                        && "user:add-signer-1".equals(request.addSignSegment().nodeDefinitions().getFirst()
                                .getParticipantPolicyText())
                        && request.addSignSegment().linkDefinitions().size() == 2
                        && "{\"nodes\":[\"add-1\"]}".equals(request.semanticJson())
                        && "{\"zoom\":1}".equals(request.layoutJson())
                        && "need review".equals(request.reason()))))
                .thenReturn(new WorkflowTaskActionResult(task, null, null, null, null));
        when(taskActionFacade.execute(eq("read"), argThat(request ->
                "task-1".equals(request.taskId())
                        && "user-1".equals(request.operatorId())
                        && "opened".equals(request.reason()))))
                .thenReturn(WorkflowTaskActionResult.of(task, null));
        when(taskActionFacade.execute(eq("read"), argThat(request ->
                "task-1".equals(request.taskId())
                        && "user-1".equals(request.operatorId())
                        && request.reason() == null)))
                .thenReturn(WorkflowTaskActionResult.of(task, null));

        mvc.perform(post("/workflow/runtime/task/task-1/actions/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "operatorId": "operator-1",
                                  "selectedDirectLinkKey": "leftRoute",
                                  "selectedReason": "choose left"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.id").value("task-1"));

        mvc.perform(post("/workflow/runtime/task/task-1/actions/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "operatorId": "operator-1",
                                  "rejectResubmitMode": "return_to_me",
                                  "reason": "not ok"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.id").value("task-1"));

        mvc.perform(post("/workflow/runtime/task/task-1/actions/transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "targetAssigneeId": "user-2",
                                  "reason": "handoff"
                                }
                                """))
                .andExpect(status().isOk());

        mvc.perform(post("/workflow/runtime/task/task-1/actions/addSign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "operatorId": "operator-1",
                                  "addSignSegment": {
                                    "nodeDefinitions": [
                                      {
                                        "nodeKey": "add-1",
                                        "nodeType": "APPROVAL",
                                        "approvalMode": "ALL",
                                        "participantPolicyText": "user:add-signer-1",
                                        "allowAddSign": true
                                      }
                                    ],
                                    "linkDefinitions": [
                                      {
                                        "routeKey": "entry-add",
                                        "sourceNodeKey": "approve",
                                        "targetNodeKey": "add-1"
                                      },
                                      {
                                        "routeKey": "exit-add",
                                        "sourceNodeKey": "add-1",
                                        "targetNodeKey": "next"
                                      }
                                    ]
                                  },
                                  "semanticJson": {"nodes":["add-1"]},
                                  "layoutJson": {"zoom":1},
                                  "reason": "need review"
                                }
                                """))
                .andExpect(status().isOk());

        mvc.perform(post("/workflow/runtime/task/task-1/actions/read")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operatorId\":\"operator-1\",\"reason\":\"opened\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.id").value("task-1"));

        mvc.perform(post("/workflow/runtime/task/task-1/read")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.id").value("task-1"));
    }

    @Test
    void shouldPassManualRouteSelectionsToTaskActionFacade() throws Exception {
        WorkflowTask task = new WorkflowTask();
        task.setId("task-structured");
        when(taskActionFacade.execute(eq("approve"), argThat(request ->
                "task-structured".equals(request.taskId())
                        && "user-1".equals(request.operatorId())
                        && request.manualRouteSelections().size() == 2
                        && "branchA".equals(request.manualRouteSelections().get(0).branchNodeKey())
                        && "routeA1".equals(request.manualRouteSelections().get(0).routeKey())
                        && "choose A1".equals(request.manualRouteSelections().get(0).selectedReason())
                        && "branchB".equals(request.manualRouteSelections().get(1).branchNodeKey())
                        && "routeB2".equals(request.manualRouteSelections().get(1).routeKey())
                        && "choose B2".equals(request.manualRouteSelections().get(1).selectedReason()))))
                .thenReturn(WorkflowTaskActionResult.of(task, null));

        mvc.perform(post("/workflow/runtime/task/task-structured/actions/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "operatorId": "operator-1",
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
    void shouldExecuteInstanceActionsThroughInstanceActionFacade() throws Exception {
        WorkflowInstance instance = instance("inst-1");
        when(instanceActionFacade.execute(eq("revoke"), argThat(request ->
                "inst-1".equals(request.instanceId())
                        && "user-1".equals(request.operatorId())
                        && "cancel".equals(request.reason()))))
                .thenReturn(new WorkflowInstanceActionResult(instance, List.of(), List.of(), List.of(), null));
        when(instanceActionFacade.execute(eq("terminate"), argThat(request ->
                "inst-1".equals(request.instanceId())
                        && "user-1".equals(request.operatorId())
                        && "stop".equals(request.reason()))))
                .thenReturn(new WorkflowInstanceActionResult(instance, List.of(), List.of(), List.of(), null));

        mvc.perform(post("/workflow/runtime/instance/inst-1/actions/revoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operatorId\":\"operator-1\",\"reason\":\"cancel\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instance.id").value("inst-1"));

        mvc.perform(post("/workflow/runtime/instance/inst-1/actions/terminate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"stop\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void shouldExposeModuleTaskPrepareAndCheckAndContinue() throws Exception {
        WorkflowModuleTaskProcessBundle bundle = moduleTaskBundle("task-1");
        WorkflowTaskActionResult actionResult = WorkflowTaskActionResult.of(new WorkflowTask(), null);
        when(moduleTaskRuntimeService.prepare("task-1", "user-1")).thenReturn(bundle);
        when(moduleTaskRuntimeService.checkAndContinue("task-1", "user-1", "done",
                "leftRoute", "choose left"))
                .thenReturn(WorkflowModuleTaskContinueResult.continued(actionResult));

        mvc.perform(get("/workflow/runtime/task/task-1/module-task/prepare"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("task-1"))
                .andExpect(jsonPath("$.workflowTaskContext.workflowTaskId").value("task-1"))
                .andExpect(jsonPath("$.workflowTaskContext.completionPolicy").value("MANUAL_CONFIRM"));

        mvc.perform(post("/workflow/runtime/task/task-1/module-task/check-and-continue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "operatorId": "operator-1",
                                  "reason": "done",
                                  "selectedDirectLinkKey": "leftRoute",
                                  "selectedReason": "choose left"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.continued").value(true));

        verify(moduleTaskRuntimeService).prepare("task-1", "user-1");
        verify(moduleTaskRuntimeService).checkAndContinue("task-1", "user-1", "done",
                "leftRoute", "choose left");
    }

    @Test
    void shouldPassManualRouteSelectionsToModuleTaskContinue() throws Exception {
        WorkflowTaskActionResult actionResult = WorkflowTaskActionResult.of(new WorkflowTask(), null);
        when(moduleTaskRuntimeService.checkAndContinue(eq("task-1"), eq("user-1"), eq("done"),
                eq(null), eq(null), argThat(selections ->
                        selections.size() == 2
                                && "branchA".equals(selections.get(0).branchNodeKey())
                                && "routeA1".equals(selections.get(0).routeKey())
                                && "choose A1".equals(selections.get(0).selectedReason())
                                && "branchB".equals(selections.get(1).branchNodeKey())
                                && "routeB2".equals(selections.get(1).routeKey()))))
                .thenReturn(WorkflowModuleTaskContinueResult.continued(actionResult));

        mvc.perform(post("/workflow/runtime/task/task-1/module-task/check-and-continue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "operatorId": "operator-1",
                                  "reason": "done",
                                  "manualRouteSelections": [
                                    {"branchNodeKey":"branchA","routeKey":"routeA1","selectedReason":"choose A1"},
                                    {"branchNodeKey":"branchB","routeKey":"routeB2","selectedReason":"choose B2"}
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.continued").value(true));
    }

    private WorkflowInstance instance(String id) {
        WorkflowInstance instance = new WorkflowInstance();
        instance.setId(id);
        instance.setDefinitionId("def-1");
        instance.setWorkflowVersionId("ver-1");
        instance.setVersionNo(1);
        instance.setModuleAlias("crm.contract");
        instance.setRecordId("record-1");
        instance.setInstanceStatus(WorkflowInstanceStatus.RUNNING);
        instance.setApprovalStatus(WorkflowApprovalStatus.PROCESSING);
        instance.setSnapshotText("{}");
        return instance;
    }

    private WorkflowModuleTaskProcessBundle moduleTaskBundle(String taskId) {
        WorkflowTaskDefinition definition = new WorkflowTaskDefinition();
        definition.setId("task-def-1");
        definition.setModuleAlias("crm.contract");
        definition.setAlias("visit");
        return new WorkflowModuleTaskProcessBundle(taskId, "inst-1", "visit", "crm.contract", "record-1",
                WorkflowModuleTaskCompletionPolicy.MANUAL_CONFIRM,
                new WorkflowModuleTaskContext(taskId, WorkflowModuleTaskCompletionPolicy.MANUAL_CONFIRM),
                definition,
                WorkflowModuleTaskEvaluation.manualConfirm(List.of()),
                null);
    }
}
