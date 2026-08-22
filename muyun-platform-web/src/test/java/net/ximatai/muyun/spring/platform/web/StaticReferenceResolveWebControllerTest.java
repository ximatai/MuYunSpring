package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.web.WebReferenceResolveMode;
import net.ximatai.muyun.spring.web.WebReferenceResolveResponse;
import net.ximatai.muyun.spring.web.WebReferenceResolveStatus;
import net.ximatai.muyun.spring.web.TenantRequestScope;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StaticReferenceResolveWebControllerTest {
    @Test
    void shouldRouteStaticReferenceResolutionThroughTheIsolatedPlatformEndpoint() throws Exception {
        StaticReferenceResolveFacade facade = mock(StaticReferenceResolveFacade.class);
        when(facade.resolve(eq("iam.department"), eq("organizationId"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new WebReferenceResolveResponse(WebReferenceResolveStatus.OK,
                        WebReferenceResolveMode.QUERY, List.of(), List.of(), 0, 20, 0));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new StaticReferenceResolveWebController(facade, mock(TenantRequestScope.class))).build();

        mvc.perform(post("/platform.module/iam.department/references/organizationId/resolve")
                        .contentType("application/json")
                        .content("{\"fuzzy\":\"总部\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("QUERY"));

        verify(facade).resolve(eq("iam.department"), eq("organizationId"), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldRequireAnActiveTenantForTenantScopedReferenceResolution() {
        StaticReferenceResolveFacade facade = mock(StaticReferenceResolveFacade.class);
        TenantRequestScope tenantRequestScope = mock(TenantRequestScope.class);
        StaticReferenceResolveWebController controller =
                new StaticReferenceResolveWebController(facade, tenantRequestScope);

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            controller.resolve("iam.department", "organizationId", null);
        }

        verify(tenantRequestScope).requireActiveTenant("iam.department");
        verify(facade).resolve("iam.department", "organizationId", null);
    }
}
