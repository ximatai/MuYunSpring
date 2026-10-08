package net.ximatai.muyun.spring.platform.web.workflow;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.platform.workflow.WorkflowBusinessTaskActionService;
import net.ximatai.muyun.spring.platform.workflow.WorkflowManualRouteSelection;
import net.ximatai.muyun.spring.web.CurrentUserWebFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
                .thenReturn(new WorkflowBusinessTaskActionService.Result(Map.of("delivered", true), null));
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
    void guideWithoutAuthenticatedOperatorDoesNotReachBusinessService() {
        var controller = new WorkflowBusinessTaskActionWebController(service);
        assertThatThrownBy(() -> controller.execute("task-1", "delivery",
                new WorkflowBusinessTaskActionWebController.Request(3, Map.of(), Map.of(), "reason", List.of())))
                .isInstanceOf(PlatformException.class).hasMessageContaining("authenticated workflow operator");
        verifyNoInteractions(service);
    }
}
