package net.ximatai.muyun.spring.iam.web;

import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.iam.role.RoleActionExecutionPolicyService;
import net.ximatai.muyun.spring.iam.role.RoleService;
import net.ximatai.muyun.spring.iam.tenant.TenantApplicationService;
import net.ximatai.muyun.spring.platform.web.ActionEndpointContextResolver;
import net.ximatai.muyun.spring.platform.web.ActionEndpointInterceptor;
import net.ximatai.muyun.spring.platform.workflow.WorkflowRuntimeReadFacade;
import net.ximatai.muyun.spring.web.CurrentUserWebFilter;
import net.ximatai.muyun.spring.web.PlatformWebExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkflowWorkbenchWebControllerTest {
    @AfterEach void cleanup() { CurrentUserContext.clear(); TenantContext.clear(); }

    @Test
    void ordinaryTenantMemberQueriesOnlyAuthenticatedParticipationWithoutRoleGrant() throws Exception {
        var runtime = mock(WorkflowRuntimeReadFacade.class);
        var roles = mock(RoleService.class);
        var applications = mock(TenantApplicationService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new WorkflowWorkbenchWebController(runtime))
                .addFilters(new CurrentUserWebFilter(() -> Optional.of(CurrentUser.tenantUser("member", "Member", "tenant-a"))))
                .addInterceptors(new ActionEndpointInterceptor(new RoleActionExecutionPolicyService(roles, applications),
                        new ActionEndpointContextResolver()))
                .setControllerAdvice(new PlatformWebExceptionHandler()).build();
        mvc.perform(post("/iam.workflow_workbench/query").param("userId", "another-user"))
                .andExpect(status().isOk());
        verify(applications).requireApplicationOpened("tenant-a", "iam");
        verify(runtime).todoCards(eq("member"), any(), any());
        verifyNoInteractions(roles);
    }

    @Test
    void anonymousUserCannotQueryPersonalWorkbench() throws Exception {
        var runtime = mock(WorkflowRuntimeReadFacade.class);
        var mvc = MockMvcBuilders.standaloneSetup(new WorkflowWorkbenchWebController(runtime))
                .addInterceptors(new ActionEndpointInterceptor(new RoleActionExecutionPolicyService(mock(RoleService.class),
                        mock(TenantApplicationService.class)), new ActionEndpointContextResolver()))
                .setControllerAdvice(new PlatformWebExceptionHandler()).build();
        mvc.perform(post("/iam.workflow_workbench/query")).andExpect(status().isUnauthorized());
        verifyNoInteractions(runtime);
    }
}
