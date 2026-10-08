package net.ximatai.muyun.spring.platform.web.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.ability.action.*;
import net.ximatai.muyun.spring.common.identity.*;
import net.ximatai.muyun.spring.platform.workflow.*;
import net.ximatai.muyun.spring.web.*;
import net.ximatai.muyun.spring.web.realtime.DataChangeRealtimePublisher;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WorkflowRuntimeMutationWebContractTest {
    @ParameterizedTest @ValueSource(strings = {
            "/record/test.order/r/actions/submitApproval", "/instance/i/actions/terminate",
            "/task/t/actions/approve", "/task/t/read", "/task/t/module-task/check-and-continue",
            "/admin/instance/i/actions/forceTerminate", "/admin/instance/i/actions/reset", "/admin/task/t/actions/forceApprove"})
    void everyWorkflowWriteUsesOneCommittedHttpAndRealtimeReceipt(String path) throws Exception {
        var tasks = mock(WorkflowTaskActionFacade.class); var instances = mock(WorkflowInstanceActionFacade.class);
        var submissions = mock(WorkflowSubmitFacade.class); var business = mock(WorkflowModuleTaskRuntimeService.class);
        var admin = mock(WorkflowAdminFacade.class); var instance = new WorkflowInstance(); instance.setId("i");
        instance.setModuleAlias("test.order"); instance.setRecordId("r");
        var task = new WorkflowTask(); task.setId("t");
        var taskResult = WorkflowTaskActionResult.of(task, null);
        var instanceResult = new WorkflowInstanceActionResult(instance, List.of(), List.of(), List.of(), null);
        var submitResult = new WorkflowSubmitResult(new WorkflowSubmitDraft(instance, null, null, null, null, null), false);
        when(tasks.execute(anyString(), any())).thenAnswer(invocation -> fact(taskResult));
        when(instances.execute(anyString(), any())).thenAnswer(invocation -> fact(instanceResult));
        when(submissions.submit(any())).thenAnswer(invocation -> fact(submitResult));
        when(business.checkAndContinue(anyString(), anyString(), any(), any(), any())).thenAnswer(invocation -> fact(WorkflowModuleTaskContinueResult.continued(taskResult)));
        when(admin.forceTerminate(any())).thenAnswer(invocation -> fact(instanceResult));
        when(admin.reset(any())).thenAnswer(invocation -> fact(instanceResult));
        when(admin.forceApprove(any())).thenAnswer(invocation -> fact(taskResult));
        var publisher = mock(DataChangeRealtimePublisher.class);
        @SuppressWarnings("unchecked") ObjectProvider<DataChangeRealtimePublisher> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(publisher);
        var mapper = new ObjectMapper();
        var mvc = MockMvcBuilders.standaloneSetup(
                new WorkflowRuntimeWebController(mock(WorkflowRuntimeReadFacade.class), tasks, instances, business, submissions, mock(WorkflowSubmitReadFacade.class)),
                new WorkflowRuntimeAdminWebController(admin))
                .addFilters(new CurrentUserWebFilter(() -> Optional.of(CurrentUser.tenantUser("actor", "Actor", "a"))))
                .addInterceptors(new BusinessMutationInterceptor())
                .setControllerAdvice(new ActionResultResponseAdvice(Class::getSimpleName, mapper, provider)).build();
        var response = mvc.perform(post("/workflow/runtime" + path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").exists())
                .andExpect(jsonPath("$.changes[0].moduleAlias").value("test.order"))
                .andExpect(jsonPath("$.changes[0].recordId").value("r"))
                .andReturn().getResponse().getContentAsString();
        var captured = org.mockito.ArgumentCaptor.forClass(CommittedChangeSet.class);
        verify(publisher).publish(captured.capture());
        assertThat(captured.getValue().changeSetId()).isEqualTo(mapper.readTree(response).path("changeSetId").asText());
        assertThat(captured.getValue().changes()).containsExactly(DataChange.recordUpdated("test.order", "r"));
        assertThat(MutationContextHolder.current()).isEmpty();
    }
    private <T> T fact(T result) {
        MutationContextHolder.current().orElseThrow().record(DataChange.recordUpdated("test.order", "r"));
        return result;
    }
}
