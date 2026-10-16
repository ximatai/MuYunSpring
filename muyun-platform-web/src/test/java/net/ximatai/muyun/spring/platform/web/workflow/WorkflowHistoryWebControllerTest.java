package net.ximatai.muyun.spring.platform.web.workflow;

import net.ximatai.muyun.spring.platform.workflow.WorkflowHistoryInstance;
import net.ximatai.muyun.spring.platform.workflow.WorkflowHistoryQueryService;
import net.ximatai.muyun.spring.platform.workflow.WorkflowHistoryTaskView;
import net.ximatai.muyun.spring.platform.workflow.WorkflowHistoryEventView;
import net.ximatai.muyun.spring.platform.workflow.WorkflowRuntimeRenderBundle;
import net.ximatai.muyun.spring.platform.workflow.WorkflowTaskKind;
import net.ximatai.muyun.spring.platform.workflow.WorkflowTaskStatus;
import net.ximatai.muyun.spring.platform.workflow.WorkflowAssignmentKind;
import net.ximatai.muyun.spring.platform.workflow.WorkflowEventType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkflowHistoryWebControllerTest {
    private final WorkflowHistoryQueryService historyQueryService = mock(WorkflowHistoryQueryService.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new WorkflowHistoryWebController(historyQueryService))
            .build();

    @Test
    void shouldQueryWorkflowHistoryByRecord() throws Exception {
        WorkflowHistoryInstance history = new WorkflowHistoryInstance();
        history.setId("history-1");
        history.setModuleAlias("sales.contract");
        history.setRecordId("record-1");
        when(historyQueryService.queryRecordHistory(eq("sales.contract"), eq("record-1"), eq("starter-1"), any()))
                .thenReturn(List.of(history));
        ArgumentCaptor<net.ximatai.muyun.database.core.orm.PageRequest> pageCaptor =
                ArgumentCaptor.forClass(net.ximatai.muyun.database.core.orm.PageRequest.class);

        mvc.perform(post("/workflow/history/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"moduleAlias\":\"sales.contract\",\"recordId\":\"record-1\","
                                + "\"startedBy\":\"starter-1\","
                                + "\"page\":{\"pageNum\":2,\"pageSize\":10}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].id").value("history-1"));

        verify(historyQueryService).queryRecordHistory(eq("sales.contract"), eq("record-1"), eq("starter-1"),
                pageCaptor.capture());
        assertThat(pageCaptor.getValue().getOffset()).isEqualTo(10);
    }

    @Test
    void shouldExposeHistoryBundleTasksAndEvents() throws Exception {
        when(historyQueryService.renderBundle("history-1"))
                .thenReturn(new WorkflowRuntimeRenderBundle("HISTORY", null, List.of(), List.of()));
        when(historyQueryService.tasks("history-1")).thenReturn(List.of());
        when(historyQueryService.events("history-1")).thenReturn(List.of());
        when(historyQueryService.taskViews("history-1")).thenReturn(List.of(new WorkflowHistoryTaskView(
                "task-1", "instance-1", "node-1", WorkflowTaskKind.APPROVAL, WorkflowTaskStatus.DONE,
                WorkflowAssignmentKind.DELEGATED, "delegate-1", "代理人", "delegate-1", "代理人", true,
                "principal-1", "原审批人", "principal-1", "原审批人", "delegate-1", "代理人", true,
                "delegation-1", "{}", false, false, "approve", null, null)));
        when(historyQueryService.eventViews("history-1")).thenReturn(List.of(new WorkflowHistoryEventView(
                WorkflowHistoryEventView.ORIGIN_TYPE_ADD_SIGN, true, "approve-source", "approve-source",
                "event-1", "instance-1", "node-1", "task-1", WorkflowEventType.TASK_COMPLETED, "approve",
                "delegate-1", "代理人", "delegate-1", "代理人", true, WorkflowAssignmentKind.DELEGATED,
                "principal-1", "原审批人", "principal-1", "原审批人", "delegate-1", "代理人", true,
                "delegation-1", "{}", false, false, "保留原文意见", null, null, null)));

        mvc.perform(get("/workflow/history/history-1/bundle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("HISTORY"));
        mvc.perform(get("/workflow/history/history-1/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records").isArray());
        mvc.perform(get("/workflow/history/history-1/tasks/view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].actualProcessUserId").value("delegate-1"))
                .andExpect(jsonPath("$.records[0].actualProcessUserTitle").value("代理人"))
                .andExpect(jsonPath("$.records[0].processedByDelegation").value(true));
        mvc.perform(get("/workflow/history/history-1/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records").isArray());
        mvc.perform(get("/workflow/history/history-1/events/view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].originType").value("ADD_SIGN"))
                .andExpect(jsonPath("$.records[0].isAddSignRoute").value(true))
                .andExpect(jsonPath("$.records[0].addSignSourceNodeKey").value("approve-source"))
                .andExpect(jsonPath("$.records[0].reason").value("保留原文意见"))
                .andExpect(jsonPath("$.records[0].operatorTitle").value("代理人"))
                .andExpect(jsonPath("$.records[0].delegationPolicyId").value("delegation-1"));
    }

    @Test
    void shouldNotExposeHistoryDeleteOnOrdinaryHistoryController() throws Exception {
        mvc.perform(post("/workflow/history/history-1/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }
}
