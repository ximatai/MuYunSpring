package net.ximatai.muyun.spring.platform.web.workflow;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.platform.workflow.WorkflowBusinessTaskActionService;
import net.ximatai.muyun.spring.platform.workflow.WorkflowManualRouteSelection;
import net.ximatai.muyun.spring.platform.workflow.*;
import net.ximatai.muyun.spring.ability.action.*;
import net.ximatai.muyun.spring.web.*;
import net.ximatai.muyun.spring.web.realtime.DataChangeRealtimePublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import net.ximatai.muyun.spring.web.CurrentUserWebFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WorkflowBusinessTaskActionWebControllerTest {
    private final WorkflowBusinessTaskActionService service = mock(WorkflowBusinessTaskActionService.class);

    @AfterEach
    void clearIdentity() { CurrentUserContext.clear(); }

    @Test
    void businessGuideCarriesTheEntireManualFrontierAndAuthenticatedOperator() throws Exception {
        var selections = List.of(new WorkflowManualRouteSelection("outer", "nested", "需要配送"),
                new WorkflowManualRouteSelection("inner", "express", "紧急到货"));
        when(service.execute("task-1", "delivery", 3, Map.of("delivered", true), Map.of(),
                "actual-user", "办理到货", selections))
                .thenReturn(result(Map.of("delivered", true)));
        var mvc = MockMvcBuilders.standaloneSetup(new WorkflowBusinessTaskActionWebController(service))
                .addFilters(new CurrentUserWebFilter(() -> Optional.of(
                        CurrentUser.tenantUser("actual-user", "Actual User", "tenant-a"))))
                .build();

        mvc.perform(post("/workflow/runtime/task/task-1/module-task/guides/delivery/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":3,"values":{"delivered":true},"payload":{},"reason":"办理到货",
                                 "operatorId":"forged-user","manualRouteSelections":[
                                   {"branchNodeKey":"outer","routeKey":"nested","selectedReason":"需要配送"},
                                   {"branchNodeKey":"inner","routeKey":"express","selectedReason":"紧急到货"}]}
                                """))
                .andExpect(status().isOk());

        verify(service).execute("task-1", "delivery", 3, Map.of("delivered", true), Map.of(),
                "actual-user", "办理到货", selections);
        verifyNoMoreInteractions(service);
    }

    @Test
    void businessOutputIsAReceiptAndPublishesTheSameStandardChangesAsHttp() throws Exception {
        var publisher = mock(DataChangeRealtimePublisher.class);
        ObjectProvider<DataChangeRealtimePublisher> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(publisher);
        var mapper = new ObjectMapper();
        when(service.execute(anyString(), anyString(), any(), any(), any(), anyString(), any(), any()))
                .thenAnswer(invocation -> {
                    assertThat(MutationContextHolder.current()).isPresent();
                    MutationContextHolder.current().orElseThrow().record(DataChange.recordUpdated("test.business", "record-1"));
                    MutationContextHolder.current().orElseThrow().message(ActionMessage.success(
                            "workflow.business-task.completed", "业务任务已完成"));
                    return result(Map.of("protectedField", "PRIVATE-VALUE", "nested", Map.of("secret", "PRIVATE-NESTED")));
                });
        var mvc = MockMvcBuilders.standaloneSetup(new WorkflowBusinessTaskActionWebController(service))
                .addFilters(new CurrentUserWebFilter(() -> Optional.of(CurrentUser.tenantUser("actor", "Actor", "tenant-a"))))
                .addInterceptors(new BusinessMutationInterceptor())
                .setControllerAdvice(new ActionResultResponseAdvice(Class::getSimpleName, mapper, provider))
                .build();
        var response = mvc.perform(post("/workflow/runtime/task/task-1/module-task/guides/delivery/execute")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":3,\"values\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value("task-1"))
                .andExpect(jsonPath("$.data.instanceId").value("instance-1"))
                .andExpect(jsonPath("$.data.businessResult").doesNotExist())
                .andExpect(jsonPath("$.message.code").value("workflow.business-task.completed"))
                .andExpect(jsonPath("$.changes[0].moduleAlias").value("test.business"))
                .andExpect(jsonPath("$.changes[0].recordId").value("record-1"))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("PRIVATE-VALUE", "PRIVATE-NESTED", "protectedField");
        var committed = org.mockito.ArgumentCaptor.forClass(CommittedChangeSet.class);
        verify(publisher).publish(committed.capture());
        assertThat(mapper.<com.fasterxml.jackson.databind.JsonNode>valueToTree(committed.getValue().changes())).isEqualTo(mapper.readTree(response).path("changes"));
        assertThat(committed.getValue().changeSetId()).isEqualTo(mapper.readTree(response).path("changeSetId").asText());
        assertThat(MutationContextHolder.current()).isEmpty();
    }

    @Test
    void failedBusinessCompletionDoesNotPublishRecordedIntents() throws Exception {
        var publisher = mock(DataChangeRealtimePublisher.class);
        ObjectProvider<DataChangeRealtimePublisher> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(publisher);
        when(service.execute(anyString(), anyString(), any(), any(), any(), anyString(), any(), any()))
                .thenAnswer(invocation -> {
                    MutationContextHolder.current().orElseThrow().record(DataChange.recordUpdated("test.business", "record-1"));
                    throw new PlatformException("业务准备尚未完成");
                });
        var mvc = MockMvcBuilders.standaloneSetup(new WorkflowBusinessTaskActionWebController(service))
                .addFilters(new CurrentUserWebFilter(() -> Optional.of(CurrentUser.tenantUser("actor", "Actor", "tenant-a"))))
                .addInterceptors(new BusinessMutationInterceptor())
                .setControllerAdvice(new PlatformWebExceptionHandler(),
                        new ActionResultResponseAdvice(Class::getSimpleName, new ObjectMapper(), provider)).build();
        mvc.perform(post("/workflow/runtime/task/task-1/module-task/guides/delivery/execute")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":3,\"values\":{}}"))
                .andExpect(status().is4xxClientError()).andExpect(jsonPath("$.changes").doesNotExist());
        verifyNoInteractions(publisher);
        assertThat(MutationContextHolder.current()).isEmpty();
    }

    private WorkflowBusinessTaskActionService.Result result(Object businessResult) {
        var task = new WorkflowTask(); task.setId("task-1"); task.setTaskStatus(WorkflowTaskStatus.DONE);
        var instance = new WorkflowInstance(); instance.setId("instance-1"); instance.setModuleAlias("test.business");
        instance.setRecordId("record-1"); instance.setInstanceStatus(WorkflowInstanceStatus.COMPLETED);
        return new WorkflowBusinessTaskActionService.Result(businessResult,
                WorkflowTaskActionResult.of(task, null, instance, null));
    }

    @Test
    void guideWithoutAuthenticatedOperatorDoesNotReachBusinessService() {
        var controller = new WorkflowBusinessTaskActionWebController(service);
        assertThatThrownBy(() -> controller.execute("task-1", "delivery",
                new WorkflowBusinessTaskActionWebController.Request(3, Map.of(), Map.of(), "reason", List.of())))
                .isInstanceOf(PlatformException.class).hasMessageContaining("authenticated workflow operator");
        verifyNoInteractions(service);
    }
}
